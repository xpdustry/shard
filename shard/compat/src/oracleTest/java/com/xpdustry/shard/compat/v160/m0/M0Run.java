package com.xpdustry.shard.compat.v160.m0;

import arc.math.*;
import arc.util.*;
import arc.util.pooling.*;
import com.xpdustry.shard.common.io.Writes;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.sync.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.logic.Processor;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.intent.*;
import com.xpdustry.shard.sim.blocks.logic.LogicBlock;
import com.xpdustry.shard.sim.blocks.transport.ConveyorBuild;
import com.xpdustry.shard.sim.blocks.turret.TurretBuild;
import com.xpdustry.shard.sim.build.Construction;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.bullet.Bullets;
import com.xpdustry.shard.sim.entity.Entity;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.rules.Rules;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.unit.BuildPlan;
import com.xpdustry.shard.sim.unit.Player;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.unit.WeaponMount;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import com.xpdustry.shard.sim.world.World;
import com.xpdustry.shard.testkit.BundledMaps;
import com.xpdustry.shard.wire.Packet;
import com.xpdustry.shard.wire.v160_7.Packets;
import mindustry.core.*;
import mindustry.entities.*;
import mindustry.entities.units.*;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.world.blocks.ConstructBlock.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The M0 harness: a bundled map hosted for survival by vanilla and by Shard (as {@code server.host} hosts it:
 * {@link HostedSystems}, {@code MapLoader.loadMap}, then {@code play}), a player who joins both and sends a client's
 * {@code clientSnapshot}s, and both stepped together, compared after every frame.
 *
 * <p>The player joins on frame 1: vanilla's {@code Player.create()} and {@code NetServer.connectConfirm} on the fake
 * connection; Shard's {@link HostInputs.Join} command and {@link HostInputs.Confirm} intent through the world's inbox.
 * Every {@value #SNAPSHOT_FRAMES} frames from its unit's spawn on, the client sends its plan queue (the
 * {@link #plan}s, as fresh plans each time, as {@code TypeIO.readPlans} makes them) and its unit's pose:
 * {@code NetServer.clientSnapshot} on vanilla's side, W2.14's {@link Snapshot} intent on Shard's, which replaces the
 * builder's queue. The client reports the pose the server has, so vanilla's wall-clock interpolation
 * ({@code SyncComp}) and position check have nothing to move.
 *
 * <p>What is compared after every frame ({@link #same}):
 * <ul>
 *   <li>the random's state (with how many draws one side is ahead), the next entity id, {@code Time.time}, the wave
 *   and its countdown;</li>
 *   <li>every building: block, position, id, team, rotation, health, enabled, efficiency, items and power; construct
 *   sites' progress and builder; turrets' reload, warmup, ammo, target, aim, shots and charge; processors' counter,
 *   variables and links; conveyors' items and their positions; unloaders' timer;</li>
 *   <li>the core's items, the player (position, death timer, unit);</li>
 *   <li>every unit in group order with its AI's target, its weapon mounts and its plans; every live bullet;</li>
 *   <li>the packed path tiles Shard's flush took against vanilla's before the frame;</li>
 *   <li>the frame's Calls byte for byte ({@link CallFrames}): construction, configs, destruction, unit deaths and
 *   spawns, player spawns, wave spawns, logic, bullets, and the replies to the client's snapshots.</li>
 * </ul>
 * and, at the end, every ported building's save chunk.
 *
 * <p>Pins, each a documented oracle nondeterminism: vanilla's pathfinder runs without its thread
 * ({@link VanillaPathfinder}); its {@code PhysicsWorld.rand} takes Shard's layer states; process-wide pools and
 * damage maps start empty; the construction sound's clock follows Shard's ({@code BuildRun}'s pin); the map's unported
 * buildings (fortress's mechanical drill draws every frame) are removed on both sides.
 */
final class M0Run{
    static final com.xpdustry.shard.content.epoch.Epoch EPOCH = Worlds.EPOCH;
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final MsavReader READER = new MsavReader(EPOCH);
    static final MapLoader LOADER = new MapLoader(EPOCH, CODECS);
    static final long SESSION = 7;
    /** A client sends a snapshot every 4 ticks ({@code NetClient.playerSyncTime}). */
    static final int SNAPSHOT_FRAMES = 4;
    /** The packets the fixture's events and intents can send, by id; vanilla's snapshots and messages aren't among them. */
    static final Set<Integer> CALLS = Set.of(Packets.BeginPlace.ID, Packets.BeginBreak.ID, Packets.ConstructFinish.ID, Packets.DeconstructFinish.ID,
        Packets.TileConfig.ID, Packets.BuildDestroyed.ID, Packets.UnitSpawn.ID, Packets.PlayerSpawn.ID, Packets.SetTile.ID, Packets.SetFlag.ID,
        Packets.SetFloor.ID, Packets.SetMapArea.ID, Packets.SetOverlay.ID, Packets.SyncVariable.ID, Packets.UnitCapDeath.ID, Packets.UnitDeath.ID,
        Packets.UnitDespawn.ID, Packets.UnitDestroy.ID, Packets.UnitEnvDeath.ID, Packets.UnitSafeDeath.ID, Packets.SpawnEffect.ID,
        Packets.CreateWeather.ID, Packets.CreateBullet.ID, Packets.UpdateGameOver.ID, Packets.RemoveQueueBlock.ID, Packets.SetPosition.ID);
    private static final Field TARGET, PHYSICS, WORLD_RAND, LAYERS, LAYER_RAND;

    static{
        try{
            TARGET = open(AIController.class.getDeclaredField("target"));
            PHYSICS = open(mindustry.async.PhysicsProcess.class.getDeclaredField("physics"));
            WORLD_RAND = open(mindustry.async.PhysicsProcess.PhysicsWorld.class.getDeclaredField("rand"));
            LAYERS = open(com.xpdustry.shard.sim.spatial.UnitPhysics.class.getDeclaredField("layers"));
            LAYER_RAND = open(Class.forName("com.xpdustry.shard.sim.spatial.UnitPhysics$Layer").getDeclaredField("rand"));
        }catch(ReflectiveOperationException e){
            throw new ExceptionInInitializerError(e);
        }
    }

    /** A plan in the client's queue, with its config as each side reads it from the wire. */
    record Plan(String block, int x, int y, int rotation, Object vanillaConfig, Object shardConfig){
    }

    /** The world, and what it sends; both replaced by {@link #saveAndLoad}. */
    World shard;
    final Rules rules;
    Observer events;
    final int coreX, coreY;
    final List<Plan> plans = new ArrayList<>();
    final Map<Integer, List<Runnable>> edits = new TreeMap<>();
    /** Calls sent so far, by packet name. */
    final Map<String, Integer> calls = new TreeMap<>();
    /** Unported buildings that update, removed before the first frame, as "block@x,y". */
    final List<String> removed = new ArrayList<>();
    mindustry.gen.Player vanillaPlayer;
    Player shardPlayer;
    int frame, snapshotId, sequence, saves;
    private int[] pathTiles;
    private boolean physicsPinned;

    /** A bundled map hosted for survival on both sides, which must agree on the random and the ids. */
    M0Run(String map){
        this(map, BundledMaps.read(map));
    }

    /** {@code file}, a map file vanilla hosts by its path {@code map}, hosted for survival on both sides. */
    M0Run(String map, byte[] file){
        Vanilla.host(map);
        SaveFile save = READER.read(file);
        rules = LOADER.hostRules(save, Gamemode.SURVIVAL);
        MapLoader.Loaded loaded = LOADER.loadMap(save, HostedSystems.template(EPOCH, 0, 4096), rules);
        MapLoader.play(loaded);
        shard = loaded.world();
        assertEquals(Mathf.rand.seed0, shard.rand().getState(0), "random after hosting");
        assertEquals(lastId(), shard.ids().peek(), "next id after hosting");
        // Vanilla.reset zeroes Time.time but not Arc's raw clock, which the next frame copies back
        Time.setInternalTime(shard.timers().time());
        var core = state.rules.defaultTeam.core();
        coreX = core.tile.x;
        coreY = core.tile.y;

        // vanilla's pathfinder without its thread, its rings repacked as Shard's are, before the first tile changes
        VanillaPathfinder.take();
        VanillaPathfinder.freshRings();
        // the map's unported buildings that update would diverge (a mechanical drill draws every frame): gone on both
        // sides; unported walls don't update, and stay
        var unported = new ArrayList<int[]>();
        for(int pos = 0; pos < shard.tiles().size(); pos++){
            if(shard.buildings().at(pos) instanceof PassthroughBuilding b && b.tile() == pos && world.build(b.tileX(), b.tileY()).block.update){
                unported.add(new int[]{b.tileX(), b.tileY()});
            }
        }
        for(int[] at : unported){
            removed.add(world.tile(at[0], at[1]).block().name + "@" + at[0] + "," + at[1]);
            world.tile(at[0], at[1]).remove();
            Vanilla.runPosted();
            shard.buildings().setBlock(at[0], at[1], EPOCH.block("air"), 0, 0, Cause.SERVER);
        }
        freshProcess();
        // the logic random as the oracle seeds GlobalVars.rand
        LogicPart.require(shard).logic().rand(new com.xpdustry.shard.common.math.Rand(mindustry.logic.GlobalVars.rand.seed0,
            mindustry.logic.GlobalVars.rand.seed1));
        events = shard.observe(1 << 14, List.of(), null);
        same("as hosted");
    }

    /**
     * Vanilla state that outlives a map, back to a fresh process's, as a Shard world starts with its own: the bullet
     * pool and {@code Groups}' free queue (a freed bullet draws an id), the lightning seed and {@code Damage}'s maps.
     */
    @SuppressWarnings("unchecked")
    private static void freshProcess(){
        try{
            open(mindustry.entities.Lightning.class.getDeclaredField("lastSeed")).setInt(null, 0);
            ((arc.struct.Seq<?>)open(Groups.class.getDeclaredField("freeQueue")).get(null)).clear();
            var bullets = ((arc.struct.ObjectMap<Class<?>, Pool<?>>)open(Pools.class.getDeclaredField("typePools")).get(null)).get(Bullet.class);
            if(bullets != null) bullets.clear();
            ((arc.struct.IntFloatMap)open(Damage.class.getDeclaredField("damages")).get(null)).clear(51);
            ((arc.struct.IntSet)open(Damage.class.getDeclaredField("collidedBlocks")).get(null)).clear(51);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    // ---- setting up, as a map editor would, and the client's queue

    /** The core's stock of {@code item}, on both sides. */
    void items(String item, int amount){
        world.build(coreX, coreY).items.set(content.item(item), amount);
        shard.buildings().at(coreX, coreY).items.set(EPOCH.item(item).id(), amount);
    }

    /** A plan the client keeps in its queue until the block stands. */
    void plan(String block, int x, int y, int rotation, Object vanillaConfig, Object shardConfig){
        plans.add(new Plan(block, x, y, rotation, vanillaConfig, shardConfig));
    }

    void plan(String block, int x, int y, int rotation){
        plan(block, x, y, rotation, null, null);
    }

    void at(int when, Runnable edit){
        edits.computeIfAbsent(when, k -> new ArrayList<>()).add(edit);
    }

    mindustry.gen.Building vanilla(int x, int y){
        return world.build(x, y);
    }

    Building ours(int x, int y){
        return shard.buildings().at(x, y);
    }

    /**
     * The player disconnects, inside a frame's edits ({@code at(frame, run::leave)}): vanilla's
     * {@code NetServer.onDisconnect} (its core unit despawns, the player is removed) and Shard's {@link HostInputs.Leave}.
     */
    void leave(){
        assertNotNull(vanillaPlayer, "a player to leave");
        NetServer.onDisconnect(vanillaPlayer, "left");
        assertTrue(shard.submit(new HostInputs.Leave(SESSION), Cause.SERVER));
        vanillaPlayer = null;
        shardPlayer = null;
    }

    /**
     * Vanilla's save of this world, loaded by both, as a server's {@code load} does ({@code SupportRun.saveAndLoad}):
     * vanilla's {@code SaveIO.load}, then playing again; Shard's {@code MapLoader.load} into a hosted world. Each
     * starts from a generator seeded with 0, fresh ids and empty pools (W2.18). Players aren't saved, so the player
     * must have {@link #leave left}.
     */
    void saveAndLoad(){
        assertNull(vanillaPlayer, "players aren't saved: leave first");
        same("before saving at frame " + frame);
        var file = new ByteArrayOutputStream();
        mindustry.io.SaveIO.write(file, new mindustry.io.SaveOptions());
        Mathf.rand.setSeed(0);
        try{
            open(EntityGroup.class.getDeclaredField("lastId")).setInt(null, 0);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
        HazardPools.empty();
        mindustry.io.SaveIO.load(new java.io.ByteArrayInputStream(file.toByteArray()), world.context);
        state.set(mindustry.core.GameState.State.playing);
        Vanilla.runPosted();
        HazardPools.empty();
        SaveFile save = READER.read(file.toByteArray());
        MapLoader.Loaded loaded = LOADER.load(save, HostedSystems.template(EPOCH, 0, 4096), LOADER.saveRules(save));
        assertNull(loaded.state().unknownDraws, "draws Shard doesn't count");
        events.close();
        shard = loaded.world();
        shardPlayer = null;
        pathTiles = null;
        VanillaPathfinder.take();
        VanillaPathfinder.freshRings();
        freshProcess();
        physicsPinned = false;
        LogicPart.require(shard).logic().rand(new com.xpdustry.shard.common.math.Rand(mindustry.logic.GlobalVars.rand.seed0,
            mindustry.logic.GlobalVars.rand.seed1));
        events = shard.observe(1 << 14, List.of(), null);
        saves++;
        same("loaded at frame " + frame);
    }

    // ---- running

    /** Runs frames, comparing after each, until {@code done} holds after one; fails past {@code limit}. Returns the frame. */
    int until(int limit, BooleanSupplier done){
        while(true){
            assertTrue(frame < limit, "done within " + limit + " frames");
            step();
            same("frame " + frame);
            if(done.getAsBoolean()) return frame;
        }
    }

    private void step(){
        frame++;
        pinPhysics();
        // what Shard's path flush at the start of this tick must reproduce: vanilla's tiles as the last frame left them
        pathTiles = VanillaPathfinder.tiles().clone();
        VanillaPathfinder.beforeFrame();
        Construction sound = Construction.of(shard);
        var sent = new ArrayList<Vanilla.Capture.Sent>();
        Vanilla.net(Vanilla.NetMode.server, () -> {
            Vanilla.captured().clear();
            if(frame == 1) join();
            else if(vanillaPlayer != null && !vanillaPlayer.dead() && (frame - 1) % SNAPSHOT_FRAMES == 0) snapshot();
            for(Runnable edit : edits.getOrDefault(frame, List.of())) edit.run();
            Vanilla.runPosted();
            long played = sound.lastPlayed(), pitched = sound.lastPitch();
            shard.tick();
            // constructFinish's place sound: vanilla's wall clock follows Shard's tick clock (BuildRun)
            boolean play = sound.lastPlayed() != played;
            boolean draw = play && sound.lastPitch() != pitched && (pitched < 0 || (sound.lastPlayed() - pitched) * 1000.0 / 60.0 >= 16 * 30);
            setConstructClock("lastPlayed", play ? 0L : com.xpdustry.shard.oracle.Vanilla.NOT_DUE);
            setConstructClock("lastTime", draw ? 0L : com.xpdustry.shard.oracle.Vanilla.NOT_DUE);
            Vanilla.frame();
            sent.addAll(Vanilla.captured());
        });
        VanillaPathfinder.settle();
        List<String> expected = CallFrames.vanilla(sent, CALLS);
        CallFrames.same(expected, shardCalls(events.drain()), "Calls of frame " + frame);
        for(String line : expected) calls.merge(line.split(" ")[2], 1, Integer::sum);
    }

    /** The player connects and confirms: vanilla's connect handler and {@code connectConfirm}; Shard's host inputs. */
    private void join(){
        vanillaPlayer = mindustry.gen.Player.create();
        // the oracle's one connection, as a new client's
        var con = Vanilla.connection();
        con.hasConnected = false;
        con.lastReceivedClientSnapshot = -1;
        con.lastReceivedClientTime = 0;
        con.rejectedRequests.clear();
        vanillaPlayer.con = con;
        vanillaPlayer.name = "alpha";
        vanillaPlayer.team(state.rules.defaultTeam);
        NetServer.connectConfirm(vanillaPlayer);
        assertTrue(shard.submit(new HostInputs.Join(SESSION, 1, "alpha", 0xffd37fff, false), Cause.SERVER));
        offer(new HostInputs.Confirm(1));
    }

    /** The client's snapshot: its queue and the pose the server has for its unit. */
    private void snapshot(){
        var unit = vanillaPlayer.unit();
        float x = unit.x, y = unit.y, rotation = unit.rotation;
        int id = ++snapshotId;
        var queue = new arc.struct.Queue<mindustry.entities.units.BuildPlan>();
        var ours = new ArrayList<BuildPlan>();
        for(Plan p : plans){
            queue.addLast(new mindustry.entities.units.BuildPlan(p.x, p.y, p.rotation, content.block(p.block), p.vanillaConfig));
            ours.add(BuildPlan.place(p.x, p.y, p.rotation, EPOCH.block(p.block), p.shardConfig));
        }
        NetServer.clientSnapshot(vanillaPlayer, id, unit.id, false, x, y, x, y, rotation, 0f, 0f, 0f, null, false, false, false, true, null, 0, queue,
            x, y, 1280f, 720f);
        offer(new Snapshot(Policy.VANILLA, id, unit.id, false, x, y, x, y, rotation, 0f, 0f, 0f, -1, false, false, false, true, -1, 0, ours,
            x, y, 1280f, 720f));
    }

    /** {@code intent} as the session's next packet, arrived at this frame's time. */
    private void offer(Intent intent){
        assertTrue(shard.inbox().offer(new IntentEnvelope(SESSION, 1, ++sequence, frame * 16_666_667L, intent)));
    }

    /** Once vanilla has its physics worlds: their stacked-body {@code Rand}s take the state of Shard's layers'. */
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

    // ---- Calls

    /** What the built-in mappings send for the frame's events, then the snapshots' replies, as {@link CallFrames} lines. */
    private List<String> shardCalls(List<WorldEvent> drained){
        var mapped = new ArrayList<WorldEvent>();
        var replies = new ArrayList<String>();
        var context = WorldWireContext.capture(shard);
        for(WorldEvent event : drained){
            switch(event){
                case IntentCall.To to -> replies.add(line("connection", to.packet(), context));
                case IntentCall.All all -> replies.add(line("all", all.packet(), context));
                case IntentCall.AllExcept all -> replies.add(line("except", all.packet(), context));
                case IntentCall.Team team -> replies.add(line("team " + team.team(), team.packet(), context));
                default -> mapped.add(event);
            }
        }
        var out = new ArrayList<>(CallFrames.shard(shard, mapped));
        out.addAll(replies);
        return out;
    }

    private static String line(String target, Packet packet, WorldWireContext context){
        var body = new Writes();
        packet.write(body, true, context);
        return target + (Packets.TYPES.get(packet.packetId()).reliable() ? " tcp " : " udp ") + Packets.TYPES.get(packet.packetId()).name() + " "
            + Hex.of(body.toByteArray());
    }

    // ---- the comparison

    /** Fails with {@code at} on the first state line that differs, after checking the random and the ids. */
    void same(String at){
        sameRandom(at);
        List<String> vanilla = vanillaState(), ours = shardState();
        for(int i = 0; i < Math.min(vanilla.size(), ours.size()); i++){
            if(!vanilla.get(i).equals(ours.get(i))) assertEquals(vanilla.get(i), ours.get(i), at);
        }
        assertEquals(String.join("\n", vanilla), String.join("\n", ours), at);
        if(pathTiles != null) samePaths(at);
    }

    /** Fails unless both generators agree, saying how many draws one side is ahead (up to 64). */
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

    private void samePaths(String at){
        var paths = shard.part(PathService.class);
        var diffs = new ArrayList<String>();
        int width = world.width();
        for(int pos = 0; pos < pathTiles.length && diffs.size() < 8; pos++){
            int ours = paths.packed(pos) & PathTile.VANILLA_BITS;
            if(pathTiles[pos] != ours) diffs.add("tile " + pos % width + "," + pos / width + " " + Integer.toHexString(pathTiles[pos]) + " vs " + Integer.toHexString(ours));
        }
        assertEquals(List.of(), diffs, at + ": packed path tiles");
    }

    List<String> vanillaState(){
        var out = new ArrayList<String>();
        out.add("next id " + lastId() + " time " + bits(Time.time) + " wave " + state.wave + " wavetime " + bits(state.wavetime));
        for(var tile : world.tiles){
            var b = tile.build;
            if(b == null || b.tile != tile) continue;
            var line = new StringBuilder(b.block.name + "@" + tile.x + "," + tile.y + " #" + b.id + " t" + b.team.id + " r" + b.rotation + " hp " + bits(b.health)
                + "/" + bits(b.maxHealth) + (b.enabled ? "" : " disabled") + " eff " + bits(b.efficiency));
            if(b.items != null && !(b instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild)) line.append(" items ").append(items(id -> b.items.get(id)));
            if(b.power != null){
                line.append(" power ").append(bits(b.power.status));
                if(b.power.graph != null) line.append(" graph in ").append(bits(b.power.graph.getLastPowerProduced())).append(" need ")
                    .append(bits(b.power.graph.getLastPowerNeeded())).append(" sat ").append(bits(b.power.graph.getSatisfaction()));
                line.append(" links ").append(b.power.links.size);
            }
            switch(b){
                case ConstructBuild site -> line.append(" site ").append(site.current.name).append(" progress ").append(bits(site.progress)).append(" builder ")
                    .append(site.lastBuilder == null ? -1 : site.lastBuilder.id).append(" config ").append(site.lastConfig != null);
                case mindustry.world.blocks.defense.turrets.Turret.TurretBuild t -> line.append(" turret ").append(turret(t));
                case mindustry.world.blocks.distribution.Conveyor.ConveyorBuild c -> {
                    line.append(" len ").append(c.len).append(" min ").append(bits(c.minitem)).append(" mid ").append(c.mid);
                    for(int i = 0; i < c.len; i++) line.append(" [").append(c.ids[i].id).append(' ').append(bits(c.xs[i])).append(',').append(bits(c.ys[i])).append(']');
                }
                case mindustry.world.blocks.storage.Unloader.UnloaderBuild u -> line.append(" unload ").append(bits(u.unloadTimer)).append(" rotations ")
                    .append(u.rotations);
                case mindustry.world.blocks.logic.LogicBlock.LogicBuild logic -> {
                    line.append(" counter ").append(logic.executor.counter.numval).append(" acc ").append(bits(logic.accumulator));
                    for(var v : logic.executor.vars) line.append(' ').append(v.name).append('=').append(v.isobj ? vanillaValue(v.objval) : Double.toString(v.numval));
                    line.append(" links");
                    for(var link : logic.links) line.append(' ').append(link.name).append('@').append(link.x).append(',').append(link.y).append(link.valid ? "" : " invalid");
                }
                default -> {}
            }
            out.add(line.toString());
        }
        out.add("core items " + items(id -> world.build(coreX, coreY).items.get(id)));
        out.add("player " + (vanillaPlayer == null ? "-" : bits(vanillaPlayer.x) + "," + bits(vanillaPlayer.y) + " death " + bits(vanillaPlayer.deathTimer)
            + " unit " + (vanillaPlayer.dead() ? "-" : vanillaPlayer.unit().id)));
        for(mindustry.gen.Unit u : Groups.unit){
            Object target = u.controller() instanceof AIController ai ? get(TARGET, ai) : null;
            var line = new StringBuilder(unit(u.id, u.type.name, u.team.id, u.x, u.y, u.rotation, u.vel.x, u.vel.y, u.health, u.elevation, u.dead, name(target)));
            for(mindustry.entities.units.WeaponMount m : u.mounts){
                line.append(" [").append(bits(m.reload)).append(' ').append(bits(m.rotation)).append(' ').append(m.totalShots).append(' ').append(bits(m.aimX))
                    .append(',').append(bits(m.aimY)).append(' ').append(m.shoot).append(' ').append(name(m.target)).append(']');
            }
            if(u.canBuild()){
                line.append(" counter ").append(bits((Float)field(u, "buildCounter"))).append(" plans");
                for(var plan : u.plans()){
                    line.append(" [").append(plan.breaking ? "break" : plan.block.name + " r" + plan.rotation).append(' ').append(plan.x).append(',').append(plan.y)
                        .append(' ').append(bits(plan.progress)).append(plan.initialized ? " init" : "").append(plan.stuck ? " stuck" : "")
                        .append(plan.config != null ? " config" : "").append(']');
                }
            }
            out.add(line.toString());
        }
        for(Bullet b : Groups.bullet){
            out.add("bullet " + b.id + " t" + b.type.id + " (" + bits(b.x) + ", " + bits(b.y) + ") v(" + bits(b.vel.x) + ", " + bits(b.vel.y) + ") time "
                + bits(b.time) + " dmg " + bits(b.damage));
        }
        return out;
    }

    List<String> shardState(){
        var out = new ArrayList<String>();
        out.add("next id " + shard.ids().peek() + " time " + bits(shard.timers().time()) + " wave " + WaveState.of(shard).wave + " wavetime "
            + bits(WaveState.of(shard).wavetime));
        var tiles = shard.tiles();
        for(int pos = 0; pos < tiles.size(); pos++){
            Building b = shard.buildings().at(pos);
            if(b == null || b.tile() != pos) continue;
            var line = new StringBuilder(b.block().name() + "@" + b.tileX() + "," + b.tileY() + " #" + b.id() + " t" + b.team + " r" + b.rotation + " hp "
                + bits(b.health) + "/" + bits(b.maxHealth) + (b.enabled ? "" : " disabled") + " eff " + bits(b.efficiency));
            if(b.items != null && !b.block().name().startsWith("core-")) line.append(" items ").append(items(id -> b.items.get(id)));
            if(b.power != null){
                line.append(" power ").append(bits(b.power.status));
                if(b.power.graph != null) line.append(" graph in ").append(bits(b.power.graph.getLastPowerProduced())).append(" need ")
                    .append(bits(b.power.graph.getLastPowerNeeded())).append(" sat ").append(bits(b.power.graph.getSatisfaction()));
                line.append(" links ").append(b.power.links.size);
            }
            switch(b){
                case com.xpdustry.shard.sim.build.ConstructBuild site -> line.append(" site ").append(EPOCH.block(site.current).name()).append(" progress ")
                    .append(bits(site.progress)).append(" builder ").append(site.lastBuilder == null ? -1 : site.lastBuilder.id()).append(" config ")
                    .append(site.lastConfig != null);
                case TurretBuild t -> line.append(" turret ").append(turret(t));
                case ConveyorBuild c -> {
                    line.append(" len ").append(c.len).append(" min ").append(bits(c.minitem)).append(" mid ").append(c.mid);
                    for(int i = 0; i < c.len; i++) line.append(" [").append(c.ids[i]).append(' ').append(bits(c.xs[i])).append(',').append(bits(c.ys[i])).append(']');
                }
                case com.xpdustry.shard.sim.blocks.distribution.Unloader u -> line.append(" unload ").append(bits(u.unloadTimer)).append(" rotations ")
                    .append(u.rotations);
                case LogicBlock logic -> {
                    Processor p = logic.processor();
                    line.append(" counter ").append(((Double)p.counterValue()).doubleValue()).append(" acc ").append(bits(p.accumulator()));
                    for(int i = 0; i < p.program().variables(); i++) line.append(' ').append(p.program().variableName(i)).append('=').append(shardValue(p.variable(i)));
                    line.append(" links");
                    for(var link : p.links()) line.append(' ').append(link.name()).append('@').append(link.x).append(',').append(link.y).append(link.valid() ? "" : " invalid");
                }
                default -> {}
            }
            out.add(line.toString());
        }
        Building core = shard.buildings().at(coreX, coreY);
        out.add("core items " + items(id -> core.items.get(id)));
        out.add("player " + (shardPlayer() == null ? "-" : bits(shardPlayer.x) + "," + bits(shardPlayer.y) + " death " + bits(shardPlayer.deathTimer)
            + " unit " + (shardPlayer.dead() ? "-" : shardPlayer.unit().id())));
        for(int i = 0; i < shard.units().size(); i++){
            Unit u = shard.units().get(i);
            Entity target = u.controller instanceof AiController ai ? ai.target : null;
            var line = new StringBuilder(unit(u.id(), u.type().name(), u.team, u.x, u.y, u.rotation, u.vel.x, u.vel.y, u.health, u.elevation, u.dead, name(target)));
            for(WeaponMount m : u.mounts){
                line.append(" [").append(bits(m.reload)).append(' ').append(bits(m.rotation)).append(' ').append(m.totalShots).append(' ').append(bits(m.aimX))
                    .append(',').append(bits(m.aimY)).append(' ').append(m.shoot).append(' ').append(name(m.target)).append(']');
            }
            if(com.xpdustry.shard.sim.build.Builder.canBuild(u)){
                line.append(" counter ").append(bits(u.buildCounter)).append(" plans");
                for(int p = 0; p < u.plans.size(); p++){
                    BuildPlan plan = u.plans.get(p);
                    line.append(" [").append(plan.breaking ? "break" : EPOCH.block(plan.block).name() + " r" + plan.rotation).append(' ').append(plan.x).append(',')
                        .append(plan.y).append(' ').append(bits(plan.progress)).append(plan.initialized ? " init" : "").append(plan.stuck ? " stuck" : "")
                        .append(plan.config != null ? " config" : "").append(']');
                }
            }
            out.add(line.toString());
        }
        Bullets t = shard.bullets();
        for(int r = 0; r < t.size(); r++){
            out.add("bullet " + t.id[r] + " t" + t.type[r] + " (" + bits(t.x[r]) + ", " + bits(t.y[r]) + ") v(" + bits(t.velX[r]) + ", " + bits(t.velY[r])
                + ") time " + bits(t.time[r]) + " dmg " + bits(t.damage[r]));
        }
        return out;
    }

    /** The session's player, once it joined. */
    Player shardPlayer(){
        if(shardPlayer == null){
            Roster roster = Roster.of(shard);
            shardPlayer = roster == null ? null : roster.player(SESSION);
        }
        return shardPlayer;
    }

    static String turret(mindustry.world.blocks.defense.turrets.Turret.TurretBuild b){
        var ammo = new StringBuilder();
        for(var entry : b.ammo) ammo.append(entry.amount).append(' ');
        return "rot " + bits(b.rotation) + " reload " + bits(b.reloadCounter) + " warmup " + bits(b.shootWarmup) + " ammo [" + ammo + "] total "
            + b.totalAmmo + " target " + nameOf(b.target) + " aim (" + bits(b.targetPos.x) + ", " + bits(b.targetPos.y) + ") shots " + b.totalShots
            + " barrel " + b.barrelCounter + " queued " + b.queuedBullets + " heat " + bits(b.heat);
    }

    static String turret(TurretBuild b){
        var ammo = new StringBuilder();
        for(int i = 0; i < b.ammoSize; i++) ammo.append(b.ammoAmounts[i]).append(' ');
        return "rot " + bits(b.turretRotation) + " reload " + bits(b.reloadCounter) + " warmup " + bits(b.shootWarmup) + " ammo [" + ammo + "] total "
            + b.totalAmmo + " target " + nameOf(b.target) + " aim (" + bits(b.targetX) + ", " + bits(b.targetY) + ") shots " + b.totalShots
            + " barrel " + b.barrelCounter + " queued " + b.queuedBullets + " heat " + bits(b.heat);
    }

    private static String unit(int id, String type, int team, float x, float y, float rotation, float vx, float vy, float health, float elevation,
                               boolean dead, String target){
        return "unit #" + id + " " + type + " t" + team + " at " + bits(x) + "," + bits(y) + " rot " + bits(rotation) + " v " + bits(vx) + "," + bits(vy)
            + " hp " + bits(health) + " el " + bits(elevation) + (dead ? " dead" : "") + " target " + target;
    }

    /** A target's name: a unit by id, a building by tile. */
    static String name(Object target){
        return nameOf(target);
    }

    static String nameOf(Object target){
        return switch(target){
            case null -> "-";
            case mindustry.gen.Unit u -> "unit#" + u.id;
            case mindustry.gen.Building b -> "build@" + b.tile.x + "," + b.tile.y;
            case Unit u -> "unit#" + u.id();
            case Building b -> "build@" + b.tileX() + "," + b.tileY();
            default -> target.getClass().getSimpleName();
        };
    }

    static String vanillaValue(Object value){
        return switch(value){
            case null -> "null";
            case Double d -> d.toString();
            case String s -> '"' + s + '"';
            case mindustry.gen.Building b -> b.block.name + "@" + b.tile.x + "," + b.tile.y;
            case mindustry.ctype.MappableContent c -> c.name;
            default -> value.getClass().getName();
        };
    }

    static String shardValue(Object value){
        return switch(value){
            case null -> "null";
            case Double d -> d.toString();
            case String s -> '"' + s + '"';
            case Building b -> b.block().name() + "@" + b.tileX() + "," + b.tileY();
            case com.xpdustry.shard.content.ContentDef c -> c.name();
            default -> value.getClass().getName();
        };
    }

    /** Every ported building's save chunk against vanilla's. */
    void chunks(){
        var ids = ContentHeader.of(EPOCH).mapping(EPOCH);
        for(var tile : world.tiles){
            var expected = tile.build;
            if(expected == null || expected.tile != tile) continue;
            Building actual = ours(tile.x, tile.y);
            if(actual instanceof PassthroughBuilding passthrough && !passthrough.hasBody()) continue;
            assertEquals(Hex.of(chunk(expected)), Hex.of(CODECS.chunk(actual, EPOCH, ids)), expected.block.name + " at (" + tile.x + ", " + tile.y + "): save chunk");
        }
    }

    static byte[] chunk(mindustry.gen.Building building){
        var bytes = new ByteArrayOutputStream();
        var out = new arc.util.io.Writes(new DataOutputStream(bytes));
        out.b(building.version());
        building.writeAll(out);
        return bytes.toByteArray();
    }

    // ---- plumbing

    /** Non-zero stock by item name, in id order. */
    static String items(IntUnaryOperator amount){
        var out = new StringJoiner(",", "[", "]");
        for(int id = 0; id < EPOCH.items(); id++) if(amount.applyAsInt(id) != 0) out.add(EPOCH.item(id).name() + "=" + amount.applyAsInt(id));
        return out.toString();
    }

    /** A float as its value and exact bits, so a last-bit difference shows. */
    static String bits(float value){
        return value + "(" + Integer.toHexString(Float.floatToRawIntBits(value)) + ")";
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

    private static Object get(Field field, Object owner){
        try{
            return field.get(owner);
        }catch(IllegalAccessException e){
            throw new AssertionError(e);
        }
    }

    /** A field declared by the object's class or one of its superclasses. */
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
