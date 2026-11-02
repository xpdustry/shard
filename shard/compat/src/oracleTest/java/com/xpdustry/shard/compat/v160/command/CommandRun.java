package com.xpdustry.shard.compat.v160.command;

import arc.math.*;
import arc.math.geom.Vec2;
import arc.struct.*;
import arc.util.pooling.*;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.sync.CallFrames;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.ai.CommandAi;
import com.xpdustry.shard.sim.ai.command.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.bullet.Bullets;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import mindustry.ai.types.*;
import mindustry.entities.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.input.*;
import mindustry.io.*;
import mindustry.world.*;
import mindustry.world.blocks.payloads.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One RTS fixture (WP72's gates) in a vanilla world and a Shard one, built from the same tile layout, ordered alike and
 * compared after every frame:
 * <ul>
 *   <li>{@code Time.time}, the random's state and the entity id counter;</li>
 *   <li>every unit in {@code Groups.unit} order: pose, health, elevation, its weapon mounts, what it carries, its AI's
 *   timers and target, and its {@code CommandAI}'s whole state: command, stances, move and attack targets, the
 *   building under the move target, the queue, its group (layer, size, its own offset, validity), the unreachable
 *   buildings, the blocking and payload timers, the transfer state, and the AI its command runs;</li>
 *   <li>every building with what it holds, and the live bullets;</li>
 *   <li>the frame's payload, unit and death Calls, byte for byte.</li>
 * </ul>
 * Orders go in before a frame: vanilla's {@code InputHandler} handlers (outside net mode, so no action filter: the
 * intents' tests hold the filter) and {@link Orders} on Shard's side. {@link #saveAt} has vanilla save mid-run; both
 * load those bytes and run on.
 *
 * <p>Pins, each a documented nondeterminism of vanilla's (ARCHITECTURE.md §1, wall-clock and thread timing):
 * <ul>
 *   <li>vanilla's control pathfinder has no thread: a pass of its loop runs before each frame Shard's
 *   {@code ControlFlush} runs one, every field to the end, and Shard's field budget is lifted alike
 *   ({@code steps(0)}); vanilla's own {@code Trigger.update} handler does the clean-up in the frame;</li>
 *   <li>{@code Vars.mainExecutor} runs in the calling thread, so a group's formation is ready as the order returns,
 *   as Shard computes it;</li>
 *   <li>the flowfield pathfinder's thread is stopped and driven per frame; {@code PhysicsWorld.rand} takes Shard's layer
 *   randoms' state; the process-wide pools start empty.</li>
 * </ul>
 *
 * <p>{@link #unpinned} drops every pin, for outcome tests (review 43): vanilla keeps its control pathfinder and flowfield
 * threads, its {@code mainExecutor} and its physics random, Shard keeps its production field budget, frames are paced at
 * 60 a second as a server's are, and nothing is compared frame by frame: the test reads the outcome.
 */
final class CommandRun implements AutoCloseable{
    static final Epoch EPOCH = Epoch.vanilla();
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final KindRegistry KINDS = KindRegistry.builtIn().withPassthrough();
    static final MsavReader READER = new MsavReader(EPOCH);
    static final MapLoader LOADER = new MapLoader(EPOCH, CODECS);
    static final Set<Integer> CALLS = Set.of(Packets.PickedBuildPayload.ID, Packets.PickedUnitPayload.ID, Packets.PayloadDropped.ID,
        Packets.UnitEnteredPayload.ID, Packets.UnitSpawn.ID, Packets.UnitDeath.ID, Packets.UnitDestroy.ID, Packets.UnitCapDeath.ID,
        Packets.UnitEnvDeath.ID, Packets.UnitDespawn.ID, Packets.BuildDestroyed.ID);
    static final List<Class<? extends WorldEvent>> EVENTS = List.of(PayloadEvent.class, CombatEvent.class, UnitEvent.class);
    private static final Field PHYSICS, WORLD_RAND, LAYERS, LAYER_RAND, TARGET, TIMER, STOP_AT, STOP_IN_RANGE, BLOCKING, BLOCKED_TIME,
        PICKUP_COOLDOWN, TRANSFER, COMMAND_CONTROLLER;

    static{
        try{
            PHYSICS = open(mindustry.async.PhysicsProcess.class.getDeclaredField("physics"));
            WORLD_RAND = open(mindustry.async.PhysicsProcess.PhysicsWorld.class.getDeclaredField("rand"));
            LAYERS = open(com.xpdustry.shard.sim.spatial.UnitPhysics.class.getDeclaredField("layers"));
            LAYER_RAND = open(Class.forName("com.xpdustry.shard.sim.spatial.UnitPhysics$Layer").getDeclaredField("rand"));
            TARGET = open(AIController.class.getDeclaredField("target"));
            TIMER = open(AIController.class.getDeclaredField("timer"));
            STOP_AT = open(CommandAI.class.getDeclaredField("stopAtTarget"));
            STOP_IN_RANGE = open(CommandAI.class.getDeclaredField("stopWhenInRange"));
            BLOCKING = open(CommandAI.class.getDeclaredField("blockingUnit"));
            BLOCKED_TIME = open(CommandAI.class.getDeclaredField("timeSpentBlocked"));
            PICKUP_COOLDOWN = open(CommandAI.class.getDeclaredField("payloadPickupCooldown"));
            TRANSFER = open(CommandAI.class.getDeclaredField("transferState"));
            COMMAND_CONTROLLER = open(CommandAI.class.getDeclaredField("commandController"));
        }catch(ReflectiveOperationException e){
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Field open(Field field){
        field.setAccessible(true);
        return field;
    }

    /** What a layout puts on a tile: a floor, and a static wall or ore, by name (or null). */
    record Cell(String floor, String wall, String overlay){
        static Cell of(String floor){
            return new Cell(floor, null, null);
        }
    }

    World shard;
    final mindustry.gen.Player vanillaPlayer;
    final int team;
    /** False for an {@link #unpinned} run. */
    final boolean pinned;
    private long nextFrame;
    private final Pool<Bullet> pool;
    private final ExecutorService executor;
    private boolean physicsPinned;
    private final Map<Integer, List<Runnable>> edits = new TreeMap<>();
    private com.xpdustry.shard.sim.world.Observer observer;
    int frame, saveAt = -1, callCount;
    /** What the fixture exercised, by name: Calls by packet, and the harness's own counts. */
    final TreeMap<String, Integer> seen = new TreeMap<>();
    /** More state a fixture compares, with the rest, at each {@link #same}. */
    Consumer<String> also = at -> {};
    /** Whether a frame's state is compared before its Calls, so a state difference that leads to a Call shows first. */
    boolean stateFirst;
    /**
     * Whether Shard ticks before vanilla's frame, so that vanilla's place sound plays, and draws its pitch, exactly when
     * Shard's did ({@link #tickShardFirst}): for fixtures where blocks are constructed.
     */
    boolean shardFirst;
    /** Run before each pinned frame, after its edits: a fixture's own pins. */
    Runnable beforeFrame = () -> {};
    /** The Shard events the Call comparison maps, {@link #EVENTS} unless a fixture adds to them. */
    List<Class<? extends WorldEvent>> events = EVENTS;
    // Shard's control flush schedule, mirrored for vanilla's passes
    private boolean started;
    private long nextPass, lastInvalidCheck;

    /**
     * A {@code width × height} world from {@code layout} on both sides, in play, waves on with no timer (so the wave
     * team is an AI team), no unit cap, no fire; the orders are the default team's.
     */
    CommandRun(int width, int height, BiFunction<Integer, Integer, Cell> layout){
        this(width, height, layout, true);
    }

    /** As {@link #CommandRun(int, int, BiFunction)}, with vanilla and Shard as they run unmodified; see the class's doc. */
    static CommandRun unpinned(int width, int height, BiFunction<Integer, Integer, Cell> layout){
        return new CommandRun(width, height, layout, false);
    }

    private CommandRun(int width, int height, BiFunction<Integer, Integer, Cell> layout, boolean pinned){
        this.pinned = pinned;
        Vanilla.reset();
        state.rules = new mindustry.game.Rules();
        state.rules.canGameOver = false;
        state.map = new mindustry.maps.Map(StringMap.of("name", "oracle"));
        world.loadGenerator(width, height, tiles -> {
            for(int y = 0; y < height; y++){
                for(int x = 0; x < width; x++){
                    Cell cell = layout.apply(x, y);
                    tiles.set(x, y, new Tile(x, y, content.block(cell.floor()), cell.overlay() == null ? mindustry.content.Blocks.air : content.block(cell.overlay()),
                        cell.wall() == null ? mindustry.content.Blocks.air : content.block(cell.wall())));
                }
            }
        });
        logic.play();
        Vanilla.runPosted();
        arc.util.Time.setInternalTime(0);
        HazardPools.empty();
        state.rules.disableUnitCap = true;
        state.rules.waves = true;
        state.rules.waveTimer = false;
        state.rules.fire = false;
        if(pinned) takePathfinders();
        Groups.updatePooling();
        Bullet.create();
        pool = Pools.get(Bullet.class, () -> null);
        pool.clear();
        executor = new AbstractExecutorService(){
            @Override
            public void execute(Runnable command){
                command.run();
            }

            @Override
            public void shutdown(){
            }

            @Override
            public List<Runnable> shutdownNow(){
                return List.of();
            }

            @Override
            public boolean isShutdown(){
                return false;
            }

            @Override
            public boolean isTerminated(){
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit){
                return true;
            }
        };
        if(pinned) mainExecutor = executor;

        shard = new World(WorldConfig.of(EPOCH, width, height, 1).withSystems(Systems.builtIn()).withKinds(KINDS));
        shard.buildings().beginLoad();
        var grid = shard.tiles();
        for(int y = 0; y < height; y++){
            for(int x = 0; x < width; x++){
                Cell cell = layout.apply(x, y);
                int pos = grid.pos(x, y);
                grid.floor(pos, EPOCH.block(cell.floor()).id());
                if(cell.overlay() != null) grid.overlay(pos, EPOCH.block(cell.overlay()).id());
                if(cell.wall() != null) grid.block(pos, EPOCH.block(cell.wall()).id());
            }
        }
        shard.buildings().endLoad(false);
        shard.part(PathService.class).invalidateAll();
        WaveSpawner.of(shard).reset(shard);
        var rules = shard.part(WorldRules.class).rules;
        rules.disableUnitCap = true;
        rules.waves = true;
        rules.waveTimer = false;
        rules.fire = false;
        WaveState.of(shard).play(rules);
        if(pinned) unlimitedFields();

        team = state.rules.defaultTeam.id;
        vanillaPlayer = mindustry.gen.Player.create();
        vanillaPlayer.name = "commander";
        vanillaPlayer.team(state.rules.defaultTeam);
        align();
    }

    /** Both sides' id counters and randoms alike from here on; Shard's id goes to vanilla's. */
    private void align(){
        int base = shard.ids().peek();
        shard.rand().setState(Mathf.rand.seed0, Mathf.rand.seed1);
        setStatic(EntityGroup.class, "lastId", base);
        setStatic(mindustry.entities.Lightning.class, "lastSeed", 0);
    }

    private static void takePathfinders(){
        VanillaPathfinder.take();
        VanillaPathfinder.freshRings();
        VanillaControl.take();
    }

    private void unlimitedFields(){
        ControlPathfinder control = ControlPathfinder.of(shard);
        if(control != null) control.steps(0);
        started = false;
    }

    @Override
    public void close(){
        pool.clear();
        mainExecutor = arc.Core.executor;
    }

    // ---------------------------------------------------------------- building the fixture

    /** {@code block} for {@code team} at (x, y), as a map editor places it. */
    void place(String block, int x, int y, int team){
        world.tile(x, y).setBlock(content.block(block), Team.get(team), 0);
        Vanilla.runPosted();
        shard.buildings().setBlock(x, y, EPOCH.block(block), team, 0, Cause.SERVER);
    }

    /** A unit of {@code type} with its type's controller at world (x, y); its id, the same on both sides. */
    int spawn(String type, int team, float x, float y){
        var theirs = content.unit(type).spawn(Team.get(team), x, y);
        Unit ours = Unit.spawn(shard, EPOCH.unit(type), team, x, y, 0f);
        assertEquals(theirs.id, ours.id(), "spawned " + type);
        return ours.id();
    }

    /** A unit of {@code type} that stands still and does nothing ({@code NoAI}), at world (x, y); its id. */
    int dummy(String type, int team, float x, float y){
        var theirs = content.unit(type).create(Team.get(team));
        theirs.controller(new NoAI());
        theirs.set(x, y);
        theirs.add();
        Unit ours = Unit.create(shard, EPOCH.unit(type), team);
        ours.controller(new NoAi());
        ours.set(x, y);
        ours.add();
        assertEquals(theirs.id, ours.id(), "a dummy " + type);
        return ours.id();
    }

    /** Damages the building at (x, y) by {@code fraction} of its health on both sides. */
    void damage(int x, int y, float fraction){
        var theirs = world.build(x, y);
        theirs.damage(theirs.maxHealth * fraction);
        Building ours = shard.buildings().at(x, y);
        ours.damage(ours.maxHealth * fraction);
    }

    /** {@code items} of {@code item} into the building at (x, y). */
    void give(int x, int y, String item, int amount){
        world.build(x, y).items.add(content.item(item), amount);
        shard.buildings().at(x, y).items.add(EPOCH.item(item).id(), amount);
    }

    /** A unit of {@code type} for {@code team} with a fresh {@code CommandAI} in place of its type's controller. */
    int commanded(String type, int team, float x, float y){
        int id = spawn(type, team, x, y);
        Groups.unit.getByID(id).controller(new CommandAI());
        unit(id).controller(new CommandAi(shard));
        return id;
    }

    Unit unit(int id){
        for(int i = 0; i < shard.units().size(); i++) if(shard.units().get(i).id() == id) return shard.units().get(i);
        return null;
    }

    CommandAi ai(int id){
        return (CommandAi)unit(id).controller;
    }

    CommandAI vanillaAi(int id){
        return (CommandAI)Groups.unit.getByID(id).controller();
    }

    void at(int when, Runnable edit){
        edits.computeIfAbsent(when, k -> new ArrayList<>()).add(edit);
    }

    /** Vanilla saves the world before frame {@code frame}; both load it and run on. */
    void saveAt(int frame){
        saveAt = frame;
    }

    private Unit[] ours(int[] ids){
        var out = new Unit[ids.length];
        for(int i = 0; i < ids.length; i++) out[i] = unit(ids[i]);
        return out;
    }

    // ---------------------------------------------------------------- orders, as the player's Calls

    /** {@code commandUnits} to a position, before frame {@code when}; {@code finalBatch} unless {@link #batch} says. */
    void move(int when, float x, float y, boolean queue, int... ids){
        order(when, ids, -1, -1, x, y, queue, true);
    }

    /** {@code commandUnits} with the units split into the client's batches of {@code size}, the last one final. */
    void batch(int when, int size, float x, float y, int... ids){
        for(int i = 0; i < ids.length; i += size){
            order(when, Arrays.copyOfRange(ids, i, Math.min(ids.length, i + size)), -1, -1, x, y, false, i + size >= ids.length);
        }
    }

    /** {@code commandUnits} on a unit target. */
    void attack(int when, int target, boolean queue, int... ids){
        order(when, ids, target, -1, 0f, 0f, queue, true);
    }

    /** {@code commandUnits} on the building at (bx, by). */
    void attackBuilding(int when, int bx, int by, int... ids){
        order(when, ids, -1, arc.math.geom.Point2.pack(bx, by), 0f, 0f, false, true);
    }

    private void order(int when, int[] ids, int unitTarget, int buildTarget, float x, float y, boolean queue, boolean last){
        at(when, () -> {
            var unit = unitTarget == -1 ? null : Groups.unit.getByID(unitTarget);
            var build = buildTarget == -1 ? null : world.build(buildTarget);
            InputHandler.commandUnits(vanillaPlayer, ids, build, unit, new Vec2(x, y), queue, last);
            Unit ourUnit = unitTarget == -1 ? null : unit(unitTarget);
            Building ourBuild = buildTarget == -1 ? null : shard.buildings().at(com.xpdustry.shard.common.geom.Point2.x(buildTarget),
                com.xpdustry.shard.common.geom.Point2.y(buildTarget));
            Orders.commandUnits(shard, team, ours(ids), new Orders.Target(ourBuild, ourUnit, x, y), queue, last);
            count("order commandUnits");
        });
    }

    /** {@code setUnitCommand}. */
    void command(int when, String name, int... ids){
        at(when, () -> {
            InputHandler.setUnitCommand(vanillaPlayer, ids, content.unitCommands().find(c -> c.name.equals(name)));
            Orders.setUnitCommand(shard, team, ours(ids), UnitCommand.of(name));
            count("order setUnitCommand " + name);
        });
    }

    /** {@code setUnitStance}. */
    void stance(int when, String name, boolean enable, int... ids){
        at(when, () -> {
            InputHandler.setUnitStance(vanillaPlayer, ids, content.unitStances().find(s -> s.name.equals(name)), enable);
            Orders.setUnitStance(shard, team, ours(ids), EPOCH.unitStances().indexOf(name), enable);
            count("order setUnitStance " + name);
        });
    }

    void count(String what){
        seen.merge(what, 1, Integer::sum);
    }

    int seen(String what){
        return seen.getOrDefault(what, 0);
    }

    // ---------------------------------------------------------------- running

    void run(int frames){
        if(!pinned){
            runUnpinned(frames);
            return;
        }
        if(observer == null) observer = shard.observe(4096, events, null);
        Vanilla.captured().clear();
        same("as built");
        int end = frame + frames;
        while(frame < end){
            frame++;
            if(frame == saveAt) saveAndLoad();
            for(Runnable edit : edits.getOrDefault(frame, List.of())) edit.run();
            beforeFrame.run();
            pinPhysics();
            controlPass();
            if(shardFirst) tickShardFirst();
            VanillaPathfinder.beforeFrame();
            int before = Vanilla.captured().size();
            Vanilla.net(Vanilla.NetMode.server, Vanilla::frame);
            VanillaPathfinder.settle();
            if(!shardFirst) shard.tick();
            var sent = new ArrayList<>(Vanilla.captured().subList(before, Vanilla.captured().size()));
            List<String> expected = CallFrames.vanilla(sent, CALLS);
            for(String line : expected) count("call " + line.split(" ")[2]);
            callCount += expected.size();
            var calls = CallFrames.shard(shard, observer.drain());
            if(stateFirst) same("after frame " + frame);
            CallFrames.same(expected, calls, "Calls in frame " + frame);
            if(!stateFirst) same("after frame " + frame);
        }
        report();
    }

    /**
     * An {@link #unpinned} run: each frame its edits, then vanilla's frame and Shard's tick, paced at 60 a second so
     * vanilla's threads get a server's time between frames; then {@link #also}, the outcome test's own reading.
     */
    private void runUnpinned(int frames){
        int end = frame + frames;
        if(nextFrame == 0) nextFrame = System.nanoTime();
        serverMode(true);
        try{
            while(frame < end){
                frame++;
                for(Runnable edit : edits.getOrDefault(frame, List.of())) edit.run();
                Vanilla.frame();
                shard.tick();
                also.accept("after frame " + frame);
                nextFrame += 1_000_000_000L / 60;
                java.util.concurrent.locks.LockSupport.parkNanos(nextFrame - System.nanoTime());
            }
        }finally{
            serverMode(false);
        }
        report();
    }

    /**
     * Vanilla's net as a server's for a whole unpinned run, set so that {@code net.client()} ({@code active && !server})
     * is never true on the way in or out: {@code server} before {@code active} entering, {@code active} first leaving.
     * Vanilla's control pathfinder and flowfield threads return for good once they see a client
     * (ControlPathfinder.java:1553), and {@code Vanilla.net}, which sets {@code active} first, gives them a frame's
     * window of exactly that each time it switches (W4.10 saw the control thread die under load).
     */
    private static void serverMode(boolean on){
        try{
            Field active = open(mindustry.net.Net.class.getDeclaredField("active")), server = open(mindustry.net.Net.class.getDeclaredField("server"));
            if(on){
                server.setBoolean(net, true);
                active.setBoolean(net, true);
            }else{
                active.setBoolean(net, false);
                server.setBoolean(net, false);
            }
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    /**
     * Shard's tick, then vanilla's place-sound clocks set from it ({@code CargoRun}'s and {@code SupportRun}'s pin, Q61):
     * vanilla's {@code constructFinish} plays the place sound at most every 32 ms of wall clock and draws its pitch only
     * when 480 ms passed since the last draw; Shard counts ticks. A frame Shard played no sound in gets a clock in the
     * future, not now: from now, a vanilla frame slower than 32 ms (a GC pause, a loaded VM) played and drew anyway.
     */
    private void tickShardFirst(){
        var sound = com.xpdustry.shard.sim.build.Construction.of(shard);
        long played = sound.lastPlayed(), pitched = sound.lastPitch();
        shard.tick();
        boolean play = sound.lastPlayed() != played;
        boolean draw = play && sound.lastPitch() != pitched && (pitched < 0 || (sound.lastPlayed() - pitched) * 1000.0 / 60.0 >= 16 * 30);
        setConstructClock("lastPlayed", play ? 0L : Vanilla.NOT_DUE);
        setConstructClock("lastTime", draw ? 0L : Vanilla.NOT_DUE);
    }

    private static void setConstructClock(String name, long value){
        try{
            open(mindustry.world.blocks.ConstructBlock.class.getDeclaredField(name)).setLong(null, value);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    /** True if vanilla's control pathfinder runs on its own thread (an {@link #unpinned} run's). */
    static boolean controlThreadRuns(){
        return VanillaControl.threadAlive();
    }

    /** What the run did, for the test's output: counts, and each unit's pose and order. */
    void report(){
        System.out.println("frame " + frame + ", " + callCount + " Calls, seen " + seen);
        for(String line : shardUnits()) System.out.println("  " + line.substring(0, Math.min(line.length(), 400)));
    }

    /**
     * Vanilla's control pathfinder thread, one pass when Shard's flush of this tick runs one (ControlPathfinder.flush):
     * {@code state.updateId} is Shard's tick then, and vanilla's frame bumps it after its {@code Trigger.update}.
     */
    private void controlPass(){
        long now = shard.ticks();
        state.updateId = now;
        if(!started){
            started = true;
            nextPass = now;
            lastInvalidCheck = now + 60;
        }
        if(now >= nextPass){
            nextPass = now + 2;
            boolean check = now - lastInvalidCheck > 60;
            if(check) lastInvalidCheck = now;
            VanillaControl.pass(controlPath, check);
            Vanilla.runPosted();
        }
    }

    private void saveAndLoad(){
        same("before saving");
        var file = new ByteArrayOutputStream();
        SaveIO.write(file, new SaveOptions());
        // both load from a generator seeded with 0, as Shard's loaded world is (Worlds.config(0))
        Mathf.rand.setSeed(0);
        // a fresh server's id counter: carried units draw their ids while the save is read
        setStatic(EntityGroup.class, "lastId", 0);
        SaveIO.load(new ByteArrayInputStream(file.toByteArray()), world.context);
        state.set(mindustry.core.GameState.State.playing);
        Vanilla.runPosted();
        HazardPools.empty();
        freshPools();
        takePathfinders();
        SaveFile save = READER.read(file.toByteArray());
        observer.close();
        shard = LOADER.load(save, Worlds.config(0), LOADER.saveRules(save)).world();
        observer = shard.observe(4096, events, null);
        unlimitedFields();
        // ids are the save's on both sides
        setStatic(EntityGroup.class, "lastId", shard.ids().peek());
        physicsPinned = false;
        count("saved and loaded");
        same("loaded");
    }

    /**
     * The bullet pool and {@code Groups}' free queue as a fresh server's: a pooled bullet draws an id when built and again
     * when freed, so what the old world left in them changes the ids the loaded one draws.
     */
    @SuppressWarnings("unchecked")
    private void freshPools(){
        try{
            ((Seq<?>)open(Groups.class.getDeclaredField("freeQueue")).get(null)).clear();
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
        pool.clear();
        setStatic(mindustry.entities.Lightning.class, "lastSeed", 0);
    }

    private void pinPhysics(){
        if(physicsPinned) return;
        try{
            Object[] vanilla = (Object[])PHYSICS.get(unitPhysics);
            if(vanilla == null) return;
            Object[] layers = (Object[])LAYERS.get(shard.part(com.xpdustry.shard.sim.spatial.UnitPhysics.class));
            for(int i = 0; i < vanilla.length; i++){
                var from = (com.xpdustry.shard.common.math.Rand)LAYER_RAND.get(layers[i]);
                ((Rand)WORLD_RAND.get(vanilla[i])).setState(from.getState(0), from.getState(1));
            }
            physicsPinned = true;
        }catch(IllegalAccessException e){
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- comparing

    void same(String at){
        List<String> vanillaUnits = vanillaUnits(), shardUnits = shardUnits();
        List<String> vanillaBuildings = vanillaBuildings(), shardBuildings = shardBuildings();
        List<String> vanillaBullets = vanillaBullets(), shardBullets = shardBullets();
        assertAll(at,
            () -> assertEquals(arc.util.Time.time, shard.timers().time(), "Time.time"),
            () -> assertEquals(Mathf.rand.seed0, shard.rand().getState(0), "random state"),
            () -> assertEquals(Mathf.rand.seed1, shard.rand().getState(1), "random state"),
            () -> assertEquals(lastId(), shard.ids().peek(), "the entity id counter"),
            () -> sameLines(vanillaUnits, shardUnits, "units"),
            () -> sameLines(vanillaBuildings, shardBuildings, "buildings"),
            () -> sameLines(vanillaBullets, shardBullets, "live bullets"),
            () -> also.accept(at));
    }

    static void sameLines(List<String> expected, List<String> actual, String what){
        for(int i = 0; i < Math.min(expected.size(), actual.size()); i++){
            if(!expected.get(i).equals(actual.get(i))) assertEquals(expected.get(i), actual.get(i), what + " #" + i);
        }
        assertEquals(expected.size(), actual.size(), what + ": " + expected + " vs " + actual);
    }

    List<String> vanillaUnits(){
        var out = new ArrayList<String>();
        for(mindustry.gen.Unit u : Groups.unit){
            var line = new StringBuilder(unit(u.id, u.type.name, u.team.id, u.x, u.y, u.rotation, u.vel.x, u.vel.y, u.health, u.elevation, u.dead));
            for(mindustry.entities.units.WeaponMount m : u.mounts){
                line.append(" [").append(bits(m.reload)).append(' ').append(bits(m.rotation)).append(' ').append(m.totalShots).append(' ')
                    .append(m.shoot).append(' ').append(name(m.target)).append(']');
            }
            if(u instanceof Payloadc carrier){
                line.append(" carries");
                for(mindustry.world.blocks.payloads.Payload p : carrier.payloads()) line.append(' ').append(payload(p));
            }
            var controller = u.controller();
            if(controller instanceof AIController ai){
                line.append(" timers ").append(Arrays.toString(((arc.util.Interval)get(TIMER, ai)).getTimes())).append(" target ").append(name(get(TARGET, ai)));
            }
            if(controller instanceof CommandAI ai) line.append(vanillaCommand(ai));
            out.add(line.toString());
        }
        return out;
    }

    private String vanillaCommand(CommandAI ai){
        var out = new StringBuilder(" | ").append(ai.command == null ? "-" : ai.command.name).append(" stances");
        for(mindustry.ai.UnitStance stance : content.unitStances()) if(ai.hasStance(stance)) out.append(' ').append(stance.id);
        out.append(" pos ").append(ai.targetPos == null ? "-" : bits(ai.targetPos.x) + "," + bits(ai.targetPos.y))
            .append(" on ").append(name(ai.targetBuild)).append(" attack ").append(name(ai.attackTarget)).append(" queue [");
        for(var entry : ai.commandQueue){
            out.append(entry instanceof Vec2 v ? bits(v.x) + "," + bits(v.y) : name(entry)).append(';');
        }
        out.append("] group ");
        mindustry.ai.UnitGroup group = ai.group;
        if(group == null){
            out.append('-');
        }else{
            out.append(group.collisionLayer).append('/').append(group.units.size).append(" #").append(ai.groupIndex).append(group.valid ? " valid" : "");
            if(group.positions != null && ai.groupIndex < group.units.size){
                out.append(' ').append(bits(group.positions[ai.groupIndex * 2])).append(',').append(bits(group.positions[ai.groupIndex * 2 + 1]));
            }
        }
        out.append(" unreachable ").append(Arrays.toString(ai.unreachableBuildings.toArray()))
            .append(" stopAt ").append(get(STOP_AT, ai)).append(" stopInRange ").append(get(STOP_IN_RANGE, ai))
            .append(" blocking ").append(get(BLOCKING, ai)).append(' ').append(bits((float)get(BLOCKED_TIME, ai)))
            .append(" cooldown ").append(bits((float)get(PICKUP_COOLDOWN, ai))).append(" transfer ").append(get(TRANSFER, ai));
        Object controller = get(COMMAND_CONTROLLER, ai);
        out.append(" runs ").append(controller == null ? "-" : controller.getClass().getSimpleName());
        if(controller instanceof AIController sub){
            out.append(' ').append(Arrays.toString(((arc.util.Interval)get(TIMER, sub)).getTimes())).append(' ').append(name(get(TARGET, sub)));
        }
        return out.toString();
    }

    List<String> shardUnits(){
        var out = new ArrayList<String>();
        for(int i = 0; i < shard.units().size(); i++){
            Unit u = shard.units().get(i);
            var line = new StringBuilder(unit(u.id(), u.type().name(), u.team, u.x, u.y, u.rotation, u.vel.x, u.vel.y, u.health, u.elevation, u.dead));
            for(com.xpdustry.shard.sim.unit.WeaponMount m : u.mounts){
                line.append(" [").append(bits(m.reload)).append(' ').append(bits(m.rotation)).append(' ').append(m.totalShots).append(' ')
                    .append(m.shoot).append(' ').append(name(m.target)).append(']');
            }
            if(u.payloads != null){
                line.append(" carries");
                for(int p = 0; p < u.payloads.size(); p++) line.append(' ').append(payload(u.payloads.get(p)));
            }
            if(u.controller instanceof AiController ai){
                line.append(" timers ").append(Arrays.toString(ai.timers)).append(" target ").append(name(ai.target));
            }
            if(u.controller instanceof CommandAi ai) line.append(shardCommand(ai));
            out.add(line.toString());
        }
        return out;
    }

    private String shardCommand(CommandAi ai){
        UnitCommand command = ai.command();
        var out = new StringBuilder(" | ").append(command == null ? "-" : command.contentName).append(" stances");
        for(int stance : ai.stanceIds()) out.append(' ').append(stance);
        out.append(" pos ").append(ai.targetPos == null ? "-" : bits(ai.targetPos.x) + "," + bits(ai.targetPos.y))
            .append(" on ").append(name(ai.targetBuild)).append(" attack ").append(name(ai.attackTarget)).append(" queue [");
        for(CommandAi.QueuedTarget entry : ai.queue){
            out.append(entry.target == null ? bits(entry.x) + "," + bits(entry.y) : name(entry.target)).append(';');
        }
        out.append("] group ");
        var group = ai.group;
        if(group == null){
            out.append('-');
        }else{
            out.append(group.collisionLayer).append('/').append(group.size).append(" #").append(ai.groupIndex).append(group.valid ? " valid" : "");
            if(group.positions != null && ai.groupIndex < group.size){
                out.append(' ').append(bits(group.positions[ai.groupIndex * 2])).append(',').append(bits(group.positions[ai.groupIndex * 2 + 1]));
            }
        }
        out.append(" unreachable ").append(Arrays.toString(Arrays.copyOf(ai.unreachableBuildings.items, ai.unreachableBuildings.size)))
            .append(" stopAt ").append(ai.stopAtTarget).append(" stopInRange ").append(ai.stopWhenInRange)
            .append(" blocking ").append(ai.blockingUnit).append(' ').append(bits(ai.timeSpentBlocked))
            .append(" cooldown ").append(bits(ai.payloadPickupCooldown)).append(" transfer ").append(ai.transferState);
        AiController sub = ai.commandController;
        out.append(" runs ").append(sub == null ? "-" : vanillaName(sub));
        if(sub != null) out.append(' ').append(Arrays.toString(sub.timers)).append(' ').append(name(sub.target));
        return out.toString();
    }

    /** Shard's AI kinds by vanilla's class names. */
    private static String vanillaName(AiController ai){
        return switch(ai.kind){
            case "repair-ai" -> "RepairAI";
            case "builder-ai", "assist-ai" -> "BuilderAI";
            case "miner-ai" -> "MinerAI";
            default -> ai.kind;
        };
    }

    private String payload(mindustry.world.blocks.payloads.Payload payload){
        return switch(payload){
            case UnitPayload u -> "unit#" + u.unit.id + "/" + u.unit.type.name + "@" + bits(u.unit.elevation);
            case BuildPayload b -> "build/" + b.block().name;
            default -> payload.getClass().getSimpleName();
        };
    }

    private String payload(com.xpdustry.shard.sim.unit.Payload payload){
        return switch(payload){
            case com.xpdustry.shard.sim.unit.Payload.UnitPayload u -> "unit#" + u.unit().id() + "/" + u.unit().type().name() + "@" + bits(u.unit().elevation);
            case com.xpdustry.shard.sim.unit.Payload.BuildPayload b -> "build/" + b.building().block().name();
        };
    }

    private static String unit(int id, String type, int team, float x, float y, float rotation, float vx, float vy, float health, float elevation, boolean dead){
        return "#" + id + " " + type + " t" + team + " at " + bits(x) + "," + bits(y) + " rot " + bits(rotation) + " v " + bits(vx) + "," + bits(vy)
            + " hp " + bits(health) + " el " + bits(elevation) + (dead ? " dead" : "");
    }

    List<String> vanillaBuildings(){
        var out = new ArrayList<String>();
        for(var tile : world.tiles){
            var b = tile.build;
            if(b == null || b.tile != tile) continue;
            var held = b.getPayload();
            out.add(b.block.name + "@" + tile.x + "," + tile.y + " t" + b.team.id + " hp " + bits(b.health) + " holds " + (held == null ? "-" : payload(held)));
        }
        return out;
    }

    List<String> shardBuildings(){
        var out = new ArrayList<String>();
        var tiles = shard.tiles();
        for(int pos = 0; pos < tiles.size(); pos++){
            Building b = shard.buildings().at(pos);
            if(b == null || b.tile() != pos) continue;
            var held = b.getPayload();
            out.add(b.block().name() + "@" + b.tileX() + "," + b.tileY() + " t" + b.team + " hp " + bits(b.health) + " holds " + (held == null ? "-" : payload(held)));
        }
        return out;
    }

    List<String> vanillaBullets(){
        var out = new ArrayList<String>();
        for(Bullet b : Groups.bullet){
            out.add(b.id + " t" + b.type.id + " (" + bits(b.x) + ", " + bits(b.y) + ") v(" + bits(b.vel.x) + ", " + bits(b.vel.y) + ") time " + bits(b.time));
        }
        return out;
    }

    List<String> shardBullets(){
        var out = new ArrayList<String>();
        Bullets t = shard.bullets();
        for(int r = 0; r < t.size(); r++){
            out.add(t.id[r] + " t" + t.type[r] + " (" + bits(t.x[r]) + ", " + bits(t.y[r]) + ") v(" + bits(t.velX[r]) + ", " + bits(t.velY[r]) + ") time " + bits(t.time[r]));
        }
        return out;
    }

    static String name(Object target){
        return switch(target){
            case null -> "-";
            case mindustry.gen.Unit u -> "unit#" + u.id;
            case mindustry.gen.Building b -> "build@" + b.tile.x + "," + b.tile.y;
            case Unit u -> "unit#" + u.id();
            case Building b -> "build@" + b.tileX() + "," + b.tileY();
            default -> target.getClass().getSimpleName();
        };
    }

    static String bits(float value){
        return value + "(" + Integer.toHexString(Float.floatToRawIntBits(value)) + ")";
    }

    private static Object get(Field field, Object owner){
        try{
            return field.get(owner);
        }catch(IllegalAccessException e){
            throw new AssertionError(e);
        }
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
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.setInt(null, value);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }
}
