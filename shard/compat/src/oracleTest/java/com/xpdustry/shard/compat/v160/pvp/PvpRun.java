package com.xpdustry.shard.compat.v160.pvp;

import arc.math.*;
import arc.util.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.sync.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.sim.build.BuildEvent;
import com.xpdustry.shard.sim.build.Builder;
import com.xpdustry.shard.sim.build.Construction;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.blocks.storage.CoreEvent;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.game.*;
import com.xpdustry.shard.sim.team.BlockPlan;
import com.xpdustry.shard.sim.team.Teams;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.unit.BuildPlan;
import com.xpdustry.shard.sim.unit.Player;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import com.xpdustry.shard.wire.v160_7.Packets;
import mindustry.ai.types.*;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.game.Team;
import mindustry.game.EventType.*;
import mindustry.gen.Groups;
import mindustry.world.blocks.ConstructBlock.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The PvP fixture's harness (WP83): an empty stone map set up in vanilla (cores of two teams, buildings, items, PvP
 * rules), saved by vanilla and loaded by both sides, as the server's {@code load} does; then vanilla and Shard stepped
 * together, with edits (players joining and leaving, build plans, damage) on given frames, and compared after every
 * frame ({@link #same}):
 * <ul>
 *   <li>the random's state, the next entity id and {@code Time.time};</li>
 *   <li>{@code state.isPaused()}, {@code state.gameOver} and the active teams in order;</li>
 *   <li>every building (block, position, id, team, rotation, health) and every team's cores, in order;</li>
 *   <li>every unit in group order (type, team, pose, health, dead, its build plans) and every player;</li>
 *   <li>each team's rebuild plans in order;</li>
 *   <li>the frame's Calls byte for byte ({@link CallFrames}), filtered on both sides to {@link #CALLS};</li>
 *   <li>the game over's frame and winner.</li>
 * </ul>
 * {@link #saveAndLoad} saves vanilla's world in the middle of play and loads it on both sides again.
 *
 * <p>Pins, as the other whole-world harnesses: vanilla's pathfinder runs without its thread; its physics layers
 * draw from Shard's layer states; the construction sound's clock follows Shard's; pools and damage maps start empty.
 *
 * <p>{@link #unpinned} drops the pins, for outcome tests (review 45, as {@code CommandRun.unpinned}): vanilla keeps its
 * control pathfinder and flowfield threads, its {@code mainExecutor}, its physics random and its wall clocks; Shard
 * keeps its production field budget; frames are paced at 60 a second as a server's are; nothing is compared frame by
 * frame, and the ids of what appears during the run may differ: the test reads the outcome ({@link #after}).
 */
final class PvpRun{
    static final Epoch EPOCH = Epoch.vanilla();
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final MsavReader READER = new MsavReader(EPOCH);
    static final MapLoader LOADER = new MapLoader(EPOCH, CODECS);
    /** The Calls a PvP game sends here, by packet id. */
    static final Set<Integer> CALLS = Set.of(Packets.BeginPlace.ID, Packets.BeginBreak.ID, Packets.ConstructFinish.ID, Packets.DeconstructFinish.ID,
        Packets.BuildDestroyed.ID, Packets.SetTile.ID, Packets.SetTeams.ID, Packets.DestroyPayload.ID, Packets.PlayerSpawn.ID, Packets.UnitDeath.ID,
        Packets.UnitDestroy.ID, Packets.UnitDespawn.ID, Packets.UnitEnvDeath.ID, Packets.UnitSafeDeath.ID, Packets.UnitCapDeath.ID, Packets.TileConfig.ID);
    static final Set<String> CALL_NAMES = new TreeSet<>();

    static{
        for(int id : CALLS) CALL_NAMES.add(Packets.TYPES.get(id).name());
    }

    private static final Field PHYSICS, WORLD_RAND, LAYERS, LAYER_RAND, LAST_ID;

    static{
        try{
            PHYSICS = open(mindustry.async.PhysicsProcess.class.getDeclaredField("physics"));
            WORLD_RAND = open(mindustry.async.PhysicsProcess.PhysicsWorld.class.getDeclaredField("rand"));
            LAYERS = open(com.xpdustry.shard.sim.spatial.UnitPhysics.class.getDeclaredField("layers"));
            LAYER_RAND = open(Class.forName("com.xpdustry.shard.sim.spatial.UnitPhysics$Layer").getDeclaredField("rand"));
            LAST_ID = open(EntityGroup.class.getDeclaredField("lastId"));
        }catch(ReflectiveOperationException e){
            throw new ExceptionInInitializerError(e);
        }
    }

    final int width, height;
    /** False for an {@link #unpinned} run. */
    final boolean pinned;
    /** After each frame of an {@link #unpinned} run, with the frame: the outcome test's own reading. */
    IntConsumer after = frame -> {};
    private long nextFrame;
    World shard;
    Observer events;
    int frame;
    final Map<Integer, List<Runnable>> edits = new TreeMap<>();
    /** Calls sent so far, by packet name. */
    final Map<String, Integer> calls = new TreeMap<>();
    /** Each side's game overs, as "frame winner". */
    final List<String> vanillaOvers = new ArrayList<>(), shardOvers = new ArrayList<>();
    /** Frames that ended paused. */
    int pausedFrames;
    /** The players on each side, by team, while they play. */
    final Map<Integer, mindustry.gen.Player> vanillaPlayers = new TreeMap<>();
    final Map<Integer, Player> shardPlayers = new TreeMap<>();
    /** The state snapshot's {@code paused} byte on each side, at the frames {@link #stateSnapshot} looked. */
    final List<String> vanillaPausedBytes = new ArrayList<>(), shardPausedBytes = new ArrayList<>();
    private boolean physicsPinned;
    /** Vanilla's control pathfinder thread, on Shard's {@code ControlFlush} schedule ({@link #controlPass}). */
    private boolean controlStarted;
    private long nextPass, lastInvalidCheck;

    PvpRun(int width, int height){
        this(width, height, true);
    }

    /** As {@link #PvpRun(int, int)}, with vanilla and Shard as they run unmodified; see the class's doc. */
    static PvpRun unpinned(int width, int height){
        return new PvpRun(width, height, false);
    }

    private PvpRun(int width, int height, boolean pinned){
        this.width = width;
        this.height = height;
        this.pinned = pinned;
        Vanilla.emptyWorld(width, height, content.block("stone"));
        state.rules.pvp = true;
        state.rules.attackMode = true;
        state.rules.waves = false;
        state.rules.canGameOver = true;
        arc.Events.on(GameOverEvent.class, e -> vanillaOvers.add(frame + " " + e.winner.id));
        if(!pinned) return;
        // a group's formation is computed at once, in the order's thread (W4.2's CommandRun pin)
        mainExecutor = new java.util.concurrent.AbstractExecutorService(){
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
            public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit){
                return true;
            }
        };
    }

    // ---- setting up, before the first load: vanilla only, as a map editor would

    void place(String block, int x, int y, Team team){
        world.tile(x, y).setBlock(content.block(block), team, 0);
        Vanilla.runPosted();
    }

    void items(int x, int y, String item, int amount){
        world.build(x, y).items.set(content.item(item), amount);
    }

    void link(int x, int y, int tx, int ty){
        world.build(x, y).configureAny(arc.math.geom.Point2.pack(tx, ty));
        Vanilla.runPosted();
    }

    /** Saves the setup and loads it on both sides. */
    void start(){
        load(save());
    }

    // ---- edits on both sides

    void at(int when, Runnable edit){
        edits.computeIfAbsent(when, k -> new ArrayList<>()).add(edit);
    }

    /** A unit with a controller that does nothing, on both sides; returns its id. */
    int unit(String type, Team team, float x, float y){
        mindustry.gen.Unit theirs = content.unit(type).create(team);
        theirs.controller(new NoAI());
        theirs.set(x, y);
        theirs.add();
        Vanilla.runPosted();
        Unit ours = Unit.create(shard, EPOCH.unit(type), team.id);
        ours.controller(new NoAi());
        ours.set(x, y);
        ours.add();
        assertEquals(theirs.id, ours.id(), "the unit's entity id");
        return ours.id();
    }

    mindustry.gen.Unit vanillaUnit(int id){
        return Groups.unit.getByID(id);
    }

    Unit shardUnit(int id){
        return EntityGroups.of(shard).byId(id) instanceof Unit u ? u : null;
    }

    /** {@code unit.addBuild(new BuildPlan(x, y, rotation, block))} on both sides. */
    void build(int unit, String block, int x, int y){
        vanillaUnit(unit).addBuild(new mindustry.entities.units.BuildPlan(x, y, 0, content.block(block)));
        Builder.addBuild(shardUnit(unit), BuildPlan.place(x, y, 0, EPOCH.block(block), null), true);
    }

    /** The building at (x, y) takes {@code amount} damage from {@code source}, on both sides. */
    void damage(int x, int y, Team source, float amount){
        world.build(x, y).damage(source, amount);
        shard.buildings().at(x, y).damage(source.id, amount);
    }

    /** A connected player of {@code team} joins on both sides; it respawns at its team's best core. */
    void join(Team team){
        mindustry.gen.Player theirs = mindustry.gen.Player.create();
        theirs.con = Vanilla.connection();
        theirs.con.hasConnected = true;
        theirs.name = "oracle-" + team.name;
        theirs.team(team);
        theirs.add();
        Player ours = Player.create(shard);
        ours.name = theirs.name;
        ours.team(team.id);
        ours.add();
        // an unpinned run may have drawn other ids by now
        if(pinned) assertEquals(theirs.id, ours.id(), "the player's entity id");
        vanillaPlayers.put(team.id, theirs);
        shardPlayers.put(team.id, ours);
    }

    /** The team's player leaves on both sides: its core unit despawns ({@code onDisconnect}), then it is removed. */
    void leave(Team team){
        mindustry.gen.Player theirs = vanillaPlayers.remove(team.id);
        Player ours = shardPlayers.remove(team.id);
        // NetServer.onDisconnect (NetServer.java:627)
        mindustry.gen.Unit u = theirs.unit();
        if(u != null && u.spawnedByCore && !u.dead) mindustry.gen.Call.unitDespawn(u);
        theirs.remove();
        Unit unit = ours.unit();
        if(unit != null && unit.spawnedByCore && !unit.dead) unit.despawn();
        ours.remove();
    }

    // ---- saves

    byte[] save(){
        var file = new ByteArrayOutputStream();
        mindustry.io.SaveIO.write(file, new mindustry.io.SaveOptions());
        return file.toByteArray();
    }

    /**
     * Loads {@code bytes} on both sides, as the server's {@code load} command: vanilla's {@code SaveIO.load}, then
     * playing; Shard's {@code MapLoader.load} into a hosted world, then {@code Teams.play}. Each starts from a generator
     * seeded with 0, fresh ids and empty pools (W2.18). Units get their do-nothing controllers back; players aren't
     * saved, so none is left.
     */
    void load(byte[] bytes){
        assertTrue(vanillaPlayers.isEmpty(), "players aren't in a save");
        Mathf.rand.setSeed(0);
        setLastId(0);
        HazardPools.empty();
        mindustry.io.SaveIO.load(new ByteArrayInputStream(bytes), world.context);
        state.set(mindustry.core.GameState.State.playing);
        Vanilla.runPosted();
        HazardPools.empty();
        SaveFile save = READER.read(bytes);
        MapLoader.Loaded loaded = LOADER.load(save, HostedSystems.template(EPOCH, 0, 4096), LOADER.saveRules(save));
        assertNull(loaded.state().unknownDraws, "draws Shard doesn't count");
        shard = loaded.world();
        Teams.of(shard).play();
        for(mindustry.gen.Unit u : Groups.unit) u.controller(new NoAI());
        for(int i = 0; i < shard.units().size(); i++) shard.units().get(i).controller(new NoAi());
        Time.setInternalTime(shard.timers().rawTime());
        if(pinned){
            VanillaPathfinder.take();
            VanillaPathfinder.freshRings();
            com.xpdustry.shard.compat.v160.command.VanillaControl.take();
            // vanilla's pass runs every field to the end: so does Shard's
            control().steps(0);
        }
        controlStarted = false;
        // a fresh game's update counter on both sides; from here each counts its own frames, paused ones left out
        state.updateId = control().updateId();
        freshProcess();
        physicsPinned = false;
        events = shard.observe(1 << 14, List.of(), null);
        if(pinned) same("loaded at frame " + frame);
    }

    com.xpdustry.shard.sim.path.ControlPathfinder control(){
        return com.xpdustry.shard.sim.path.ControlPathfinder.of(shard);
    }

    /** A unit of {@code team} with a fresh {@code CommandAI}, on both sides; returns its id. */
    int commanded(String type, Team team, float x, float y){
        int id = unit(type, team, x, y);
        vanillaUnit(id).controller(new CommandAI());
        shardUnit(id).controller(new com.xpdustry.shard.sim.ai.CommandAi(shard));
        return id;
    }

    /** {@code commandUnits} to (x, y), as the team's player orders it: a final batch, not queued. */
    void move(Team team, float x, float y, int... ids){
        mindustry.input.InputHandler.commandUnits(vanillaPlayers.get(team.id), ids, null, null, new arc.math.geom.Vec2(x, y), false, true);
        var ours = new Unit[ids.length];
        for(int i = 0; i < ids.length; i++) ours[i] = shardUnit(ids[i]);
        com.xpdustry.shard.sim.ai.command.Orders.commandUnits(shard, team.id, ours, new com.xpdustry.shard.sim.ai.command.Orders.Target(null, null, x, y), false, true);
    }

    /**
     * Vanilla's control pathfinder thread, one pass when Shard's flush of this tick runs one ({@code ControlFlush}, every
     * 2 ticks of the world's clock, paused or not, as the thread's wall clock runs through a pause). Unlike W4.2's
     * {@code CommandRun}, {@code state.updateId} is left to vanilla's own frames: it is compared, not pinned.
     */
    private void controlPass(){
        long now = shard.ticks();
        if(!controlStarted){
            controlStarted = true;
            nextPass = now;
            lastInvalidCheck = now + 60;
        }
        if(now >= nextPass){
            nextPass = now + 2;
            boolean check = now - lastInvalidCheck > 60;
            if(check) lastInvalidCheck = now;
            com.xpdustry.shard.compat.v160.command.VanillaControl.pass(controlPath, check);
            Vanilla.runPosted();
        }
    }

    void saveAndLoad(){
        load(save());
    }

    static void freshProcess(){
        try{
            open(mindustry.entities.Lightning.class.getDeclaredField("lastSeed")).setInt(null, 0);
            ((arc.struct.Seq<?>)open(Groups.class.getDeclaredField("freeQueue")).get(null)).clear();
            ((arc.struct.IntFloatMap)open(Damage.class.getDeclaredField("damages")).get(null)).clear(51);
            ((arc.struct.IntSet)open(Damage.class.getDeclaredField("collidedBlocks")).get(null)).clear(51);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    // ---- running

    /** Runs to frame {@code last}, comparing after each; an {@link #unpinned} run only reads {@link #after} each. */
    void runTo(int last){
        if(!pinned){
            runUnpinned(last);
            return;
        }
        while(frame < last){
            step();
            same("frame " + frame);
            if(shard.paused()) pausedFrames++;
        }
    }

    /**
     * Each frame its edits on both sides, then vanilla's frame and Shard's tick, paced at 60 a second so vanilla's
     * threads get a server's time between frames; vanilla's net is a server's for the whole run, set as
     * {@code CommandRun.serverMode} sets it so its pathfinder threads never see a client.
     */
    private void runUnpinned(int last){
        if(nextFrame == 0) nextFrame = System.nanoTime();
        serverMode(true);
        try{
            while(frame < last){
                frame++;
                for(Runnable edit : edits.getOrDefault(frame, List.of())) edit.run();
                Vanilla.runPosted();
                Vanilla.frame();
                shard.tick();
                for(WorldEvent event : events.drain()){
                    if(event instanceof GameEvent.GameOver over) shardOvers.add(frame + " " + over.winner());
                    if(event instanceof com.xpdustry.shard.sim.team.CoreChangeEvent change) shardCoreChanges.add(frame + " " + change.pos() + " t" + change.team());
                }
                if(shard.paused()) pausedFrames++;
                after.accept(frame);
                nextFrame += 1_000_000_000L / 60;
                java.util.concurrent.locks.LockSupport.parkNanos(nextFrame - System.nanoTime());
            }
        }finally{
            serverMode(false);
        }
    }

    /** Each side's {@code CoreChangeEvent}s during the run, as "frame pos team"; vanilla's once {@link #coreChanges} listens. */
    final List<String> vanillaCoreChanges = new ArrayList<>(), shardCoreChanges = new ArrayList<>();

    /** Records vanilla's {@code CoreChangeEvent}s as {@link #vanillaCoreChanges}. */
    void coreChanges(){
        arc.Events.on(CoreChangeEvent.class, e -> {
            if(frame > 0) vanillaCoreChanges.add(frame + " " + e.core.pos() + " t" + e.core.team.id);
        });
    }

    /** CommandRun.serverMode: {@code server} before {@code active} entering, {@code active} first leaving. */
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

    private void step(){
        frame++;
        pinPhysics();
        VanillaPathfinder.beforeFrame();
        Construction sound = Construction.of(shard);
        Vanilla.net(Vanilla.NetMode.server, this::controlPass);
        var sent = new ArrayList<Vanilla.Capture.Sent>();
        Vanilla.net(Vanilla.NetMode.server, () -> {
            Vanilla.captured().clear();
            for(Runnable edit : edits.getOrDefault(frame, List.of())) edit.run();
            Vanilla.runPosted();
            long played = sound.lastPlayed(), pitched = sound.lastPitch();
            shard.tick();
            boolean play = sound.lastPlayed() != played;
            boolean draw = play && sound.lastPitch() != pitched && (pitched < 0 || (sound.lastPlayed() - pitched) * 1000.0 / 60.0 >= 16 * 30);
            setConstructClock("lastPlayed", play ? 0L : Vanilla.NOT_DUE);
            setConstructClock("lastTime", draw ? 0L : Vanilla.NOT_DUE);
            Vanilla.frame();
            sent.addAll(Vanilla.captured());
        });
        VanillaPathfinder.settle();
        List<String> expected = CallFrames.vanilla(sent, CALLS);
        List<WorldEvent> drained = events.drain();
        for(WorldEvent event : drained){
            if(event instanceof GameEvent.GameOver over) shardOvers.add(frame + " " + over.winner());
        }
        var ours = new ArrayList<String>();
        for(String line : CallFrames.shard(shard, drained)) if(CALL_NAMES.contains(line.split(" ")[2])) ours.add(line);
        CallFrames.same(expected, ours, "Calls of frame " + frame);
        for(String line : expected) calls.merge(line.split(" ")[2], 1, Integer::sum);
    }

    /**
     * The state snapshot each side would send now: vanilla's {@code writeStateSnapshot} bytes, decoded, and Shard's
     * {@link GameState}; keeps both {@code paused} values.
     */
    void stateSnapshot(){
        var sent = new ArrayList<Vanilla.Capture.Sent>();
        Vanilla.net(Vanilla.NetMode.server, () -> {
            Vanilla.captured().clear();
            try{
                netServer.writeStateSnapshot();
            }catch(IOException e){
                throw new UncheckedIOException(e);
            }
            sent.addAll(Vanilla.captured());
        });
        var frames = sent.stream().filter(s -> s.frame() != null && s.frame().id() == Packets.StateSnapshot.ID).toList();
        assertEquals(1, frames.size(), "one state snapshot");
        Object paused = Oracle.decodePacket(Packets.StateSnapshot.ID, frames.getFirst().frame().body()).fields().stream()
            .filter(f -> f.name().equals("paused")).findFirst().orElseThrow().value();
        vanillaPausedBytes.add(frame + " " + paused);
        GameState ours = GameState.of(shard, new MapState(), GameState.TPS, 0);
        var body = new com.xpdustry.shard.common.io.Writes();
        ours.packet().write(body, true, WorldWireContext.capture(shard));
        byte[] bytes = body.toByteArray();
        // wavetime f32, wave i32, enemies i32, then paused
        shardPausedBytes.add(frame + " " + (bytes[12] != 0));
        assertEquals(ours.paused(), bytes[12] != 0);
    }

    private void pinPhysics(){
        if(physicsPinned) return;
        try{
            Object[] vanilla = (Object[])PHYSICS.get(unitPhysics);
            if(vanilla == null) return;
            Object[] ours = (Object[])LAYERS.get(shard.part(com.xpdustry.shard.sim.spatial.UnitPhysics.class));
            for(int i = 0; i < vanilla.length; i++){
                var from = (com.xpdustry.shard.common.math.Rand)LAYER_RAND.get(ours[i]);
                ((Rand)WORLD_RAND.get(vanilla[i])).setState(from.getState(0), from.getState(1));
            }
            physicsPinned = true;
        }catch(IllegalAccessException e){
            throw new IllegalStateException(e);
        }
    }

    // ---- the comparison

    void same(String at){
        sameRandom(at);
        List<String> vanilla = vanillaState(), ours = shardState();
        for(int i = 0; i < Math.min(vanilla.size(), ours.size()); i++){
            if(!vanilla.get(i).equals(ours.get(i))) assertEquals(vanilla.get(i), ours.get(i), at);
        }
        assertEquals(String.join("\n", vanilla), String.join("\n", ours), at);
        assertEquals(vanillaOvers, shardOvers, at + ": game overs");
    }

    private void sameRandom(String at){
        long v0 = Mathf.rand.seed0, v1 = Mathf.rand.seed1;
        if(v0 == shard.rand().getState(0) && v1 == shard.rand().getState(1)) return;
        var ours = new com.xpdustry.shard.common.math.Rand(shard.rand().getState(0), shard.rand().getState(1));
        for(int i = 1; i <= 64; i++){
            ours.nextLong();
            if(ours.seed0 == v0 && ours.seed1 == v1) fail(at + ": random state, Shard " + i + " draws behind vanilla");
        }
        var theirs = new com.xpdustry.shard.common.math.Rand(v0, v1);
        for(int i = 1; i <= 64; i++){
            theirs.nextLong();
            if(theirs.seed0 == shard.rand().getState(0) && theirs.seed1 == shard.rand().getState(1)) fail(at + ": random state, Shard " + i + " draws ahead of vanilla");
        }
        fail(at + ": random state differs by more than 64 draws");
    }

    List<String> vanillaState(){
        var out = new ArrayList<String>();
        out.add("next id " + lastId() + " time " + bits(Time.time) + " paused " + state.isPaused() + " over " + state.gameOver);
        out.add("tick " + state.tick + " updateId " + state.updateId + " wavetime " + bits(state.wavetime) + " wave " + state.wave + " enemies " + state.enemies);
        var active = new StringJoiner(" ", "active [", "]");
        // the list as it stands: getActive() would drop teams, which the games do on their own reads
        for(var data : state.teams.active) active.add(data.team.id + ":" + data.cores.size);
        out.add(active.toString());
        for(var tile : world.tiles){
            var b = tile.build;
            if(b == null || b.tile != tile) continue;
            var line = new StringBuilder(b.block.name + "@" + tile.x + "," + tile.y + " #" + b.id + " t" + b.team.id + " r" + b.rotation + " hp " + bits(b.health)
                + "/" + bits(b.maxHealth));
            if(b instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild core){
                line.append(" items ").append(items(id -> core.items.get(id))).append(" iframes ").append(bits(core.iframes)).append(" last ").append(core.lastDamage.id);
            }
            if(b.power != null) line.append(" links ").append(b.power.links.size);
            if(b instanceof ConstructBuild site){
                line.append(" site ").append(site.current.name).append(" progress ").append(bits(site.progress)).append(" builder ")
                    .append(site.lastBuilder == null ? -1 : site.lastBuilder.id);
            }
            out.add(line.toString());
        }
        for(Team team : new Team[]{Team.sharded, Team.crux, Team.derelict}){
            var plans = new StringBuilder("plans " + team.id);
            for(var plan : team.data().plans){
                plans.append(" [").append(plan.block.name).append(' ').append(plan.x).append(',').append(plan.y).append(plan.removed ? " removed" : "").append(']');
            }
            out.add(plans.toString());
        }
        for(mindustry.gen.Player p : Groups.player){
            out.add("player #" + p.id + " t" + p.team().id + " unit " + (p.dead() ? "-" : p.unit().id) + " at " + bits(p.x) + "," + bits(p.y));
        }
        for(mindustry.gen.Unit u : Groups.unit){
            var line = new StringBuilder(unit(u.id, u.type.name, u.team.id, u.x, u.y, u.rotation, u.health, u.dead));
            if(u.canBuild()){
                line.append(" plans");
                for(var plan : u.plans()){
                    line.append(" [").append(plan.breaking ? "break" : plan.block.name).append(' ').append(plan.x).append(',').append(plan.y).append(' ')
                        .append(bits(plan.progress)).append(plan.initialized ? " init" : "").append(plan.stuck ? " stuck" : "").append(']');
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    List<String> shardState(){
        var out = new ArrayList<String>();
        WaveState waves = WaveState.of(shard);
        out.add("next id " + shard.ids().peek() + " time " + bits(shard.timers().time()) + " paused " + shard.paused() + " over " + waves.gameOver);
        // state.tick is logic's @tick clock (LogicVars), which a pause stops with the rest
        out.add("tick " + com.xpdustry.shard.sim.logic.LogicPart.require(shard).tick() + " updateId " + control().updateId() + " wavetime " + bits(waves.wavetime) + " wave " + waves.wave
            + " enemies " + waves.enemies);
        Teams teams = Teams.of(shard);
        var active = new StringJoiner(" ", "active [", "]");
        var list = teams.activeTeams();
        for(int i = 0; i < list.size; i++) active.add(list.get(i) + ":" + teams.cores(list.get(i)).size());
        out.add(active.toString());
        var tiles = shard.tiles();
        for(int pos = 0; pos < tiles.size(); pos++){
            Building b = shard.buildings().at(pos);
            if(b == null || b.tile() != pos) continue;
            var line = new StringBuilder(b.block().name() + "@" + b.tileX() + "," + b.tileY() + " #" + b.id() + " t" + b.team + " r" + b.rotation + " hp "
                + bits(b.health) + "/" + bits(b.maxHealth));
            if(b instanceof com.xpdustry.shard.sim.blocks.storage.Core core){
                line.append(" items ").append(items(id -> core.items.get(id))).append(" iframes ").append(bits(core.iframes)).append(" last ").append(core.lastDamage());
            }
            if(b.power != null) line.append(" links ").append(b.power.links.size);
            if(b instanceof com.xpdustry.shard.sim.build.ConstructBuild site){
                line.append(" site ").append(EPOCH.block(site.current).name()).append(" progress ").append(bits(site.progress)).append(" builder ")
                    .append(site.lastBuilder == null ? -1 : site.lastBuilder.id());
            }
            out.add(line.toString());
        }
        for(int team : new int[]{Team.sharded.id, Team.crux.id, Team.derelict.id}){
            var plans = new StringBuilder("plans " + team);
            for(BlockPlan plan : teams.get(team).plans()){
                plans.append(" [").append(EPOCH.block(plan.block).name()).append(' ').append(plan.x).append(',').append(plan.y).append(plan.removed ? " removed" : "").append(']');
            }
            out.add(plans.toString());
        }
        Players players = Players.of(shard);
        for(int i = 0; i < players.list().size(); i++){
            Player p = players.list().get(i);
            out.add("player #" + p.id() + " t" + p.team + " unit " + (p.dead() ? "-" : p.unit().id()) + " at " + bits(p.x) + "," + bits(p.y));
        }
        for(int i = 0; i < shard.units().size(); i++){
            Unit u = shard.units().get(i);
            var line = new StringBuilder(unit(u.id(), u.type().name(), u.team, u.x, u.y, u.rotation, u.health, u.dead));
            if(Builder.canBuild(u)){
                line.append(" plans");
                for(int p = 0; p < u.plans.size(); p++){
                    BuildPlan plan = u.plans.get(p);
                    line.append(" [").append(plan.breaking ? "break" : EPOCH.block(plan.block).name()).append(' ').append(plan.x).append(',').append(plan.y)
                        .append(' ').append(bits(plan.progress)).append(plan.initialized ? " init" : "").append(plan.stuck ? " stuck" : "").append(']');
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    private static String unit(int id, String type, int team, float x, float y, float rotation, float health, boolean dead){
        return "unit #" + id + " " + type + " t" + team + " at " + bits(x) + "," + bits(y) + " rot " + bits(rotation) + " hp " + bits(health) + (dead ? " dead" : "");
    }

    // ---- what happened, for the fixture's own assertions

    String vanillaBlock(int x, int y){
        var b = world.build(x, y);
        return b == null ? "air" : b.block.name + " t" + b.team.id;
    }

    String shardBlock(int x, int y){
        Building b = shard.buildings().at(x, y);
        return b == null ? "air" : b.block().name() + " t" + b.team;
    }

    // ---- plumbing

    static String items(IntUnaryOperator amount){
        var out = new StringJoiner(",", "[", "]");
        for(int id = 0; id < EPOCH.items(); id++) if(amount.applyAsInt(id) != 0) out.add(EPOCH.item(id).name() + "=" + amount.applyAsInt(id));
        return out.toString();
    }

    static String bits(float value){
        return value + "(" + Integer.toHexString(Float.floatToRawIntBits(value)) + ")";
    }

    static int lastId(){
        try{
            return LAST_ID.getInt(null);
        }catch(IllegalAccessException e){
            throw new AssertionError(e);
        }
    }

    static void setLastId(int value){
        try{
            LAST_ID.setInt(null, value);
        }catch(IllegalAccessException e){
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

    private static Field open(Field field){
        field.setAccessible(true);
        return field;
    }
}
