package com.xpdustry.shard.compat.v160.support;

import arc.math.*;
import arc.util.*;
import arc.util.pooling.*;
import com.xpdustry.shard.common.geom.Point2;
import com.xpdustry.shard.common.io.Writes;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.sync.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.intent.*;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.build.Construction;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.bullet.Bullets;
import com.xpdustry.shard.sim.entity.Entity;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.rules.Rules;
import com.xpdustry.shard.sim.team.*;
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
import mindustry.ai.types.*;
import mindustry.core.*;
import mindustry.entities.*;
import mindustry.entities.units.*;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.net.*;
import mindustry.world.blocks.ConstructBlock.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The harness for the support units (WP45, WP70): a bundled map hosted for survival by vanilla and by Shard (as
 * {@code server.host} hosts it: {@link HostedSystems}, {@code MapLoader.loadMap}, then {@code play}), units of the
 * player team spawned on both sides, an optional player who joins and sends a client's {@code clientSnapshot}s and item
 * Calls, and both stepped together, compared after every frame. It is W2.22's {@code M0Run} with the state this row
 * makes live added to the comparison.
 *
 * <p>Compared after every frame ({@link #same}):
 * <ul>
 *   <li>the random's state (with how many draws one side is ahead), the next entity id, {@code Time.time};</li>
 *   <li>every building (block, position, id, team, rotation, health, items), construct sites' progress and builder;</li>
 *   <li>the core's items, the team's rebuild plans in order (with their {@code removed} flags), the player;</li>
 *   <li>every unit in group order: pose, health, item stack, mine tile and timer, build counter and plans, weapon
 *   mounts, and its AI: the AI a command runs, its four timers and its own state (a miner's item, ore and phase; a
 *   builder's followed units, last plan and timers; a repairer's target);</li>
 *   <li>every live bullet;</li>
 *   <li>the frame's Calls byte for byte ({@link CallFrames}): item transfers, construction, deaths and spawns, and the
 *   intents' forwards and replies.</li>
 * </ul>
 *
 * <p>Pins, each a documented oracle nondeterminism (W2.22's): vanilla's pathfinder runs without its thread; its
 * {@code PhysicsWorld.rand} takes Shard's layer states; process-wide pools and damage maps start empty; the
 * construction sound's clock follows Shard's; the map's unported buildings that update are removed on both sides.
 */
final class SupportRun{
    static final com.xpdustry.shard.content.epoch.Epoch EPOCH = Worlds.EPOCH;
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final MsavReader READER = new MsavReader(EPOCH);
    static final MapLoader LOADER = new MapLoader(EPOCH, CODECS);
    static final long SESSION = 7;
    static final int SNAPSHOT_FRAMES = 4;
    static final Policy POLICY = Policy.VANILLA;
    /** The packets the fixtures' events and intents can send, by id. */
    static final Set<Integer> CALLS = Set.of(Packets.BeginPlace.ID, Packets.BeginBreak.ID, Packets.ConstructFinish.ID, Packets.DeconstructFinish.ID,
        Packets.TileConfig.ID, Packets.BuildDestroyed.ID, Packets.UnitSpawn.ID, Packets.PlayerSpawn.ID, Packets.SetTile.ID, Packets.UnitCapDeath.ID,
        Packets.UnitDeath.ID, Packets.UnitDespawn.ID, Packets.UnitDestroy.ID, Packets.UnitEnvDeath.ID, Packets.UnitSafeDeath.ID, Packets.SpawnEffect.ID,
        Packets.CreateBullet.ID, Packets.RemoveQueueBlock.ID, Packets.SetPosition.ID, Packets.TransferItemTo.ID, Packets.TakeItems.ID,
        Packets.TransferItemToUnit.ID, Packets.TransferItemEffect.ID, Packets.RequestItem.ID, Packets.TransferInventory.ID,
        // ground zero's objectives (W3.12)
        Packets.CompleteObjective.ID, Packets.ClearObjectives.ID, Packets.SetObjectives.ID, Packets.CreateMarker.ID, Packets.RemoveMarker.ID,
        Packets.UpdateMarker.ID, Packets.UpdateMarkerText.ID, Packets.UpdateMarkerTexture.ID);
    private static final Field TARGET, COMMAND_CONTROLLER, PHYSICS, WORLD_RAND, LAYERS, LAYER_RAND;

    static{
        try{
            TARGET = open(AIController.class.getDeclaredField("target"));
            COMMAND_CONTROLLER = open(CommandAI.class.getDeclaredField("commandController"));
            PHYSICS = open(mindustry.async.PhysicsProcess.class.getDeclaredField("physics"));
            WORLD_RAND = open(mindustry.async.PhysicsProcess.PhysicsWorld.class.getDeclaredField("rand"));
            LAYERS = open(com.xpdustry.shard.sim.spatial.UnitPhysics.class.getDeclaredField("layers"));
            LAYER_RAND = open(Class.forName("com.xpdustry.shard.sim.spatial.UnitPhysics$Layer").getDeclaredField("rand"));
        }catch(ReflectiveOperationException e){
            throw new ExceptionInInitializerError(e);
        }
    }

    record Plan(String block, int x, int y, int rotation){
    }

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
    /** Whether a player joins on frame 1. */
    boolean withPlayer;
    /** The tile the client mines, as its snapshots say: (x, y), or null. */
    int[] mining;
    mindustry.gen.Player vanillaPlayer;
    Player shardPlayer;
    int frame, snapshotId, sequence;
    private boolean physicsPinned;

    SupportRun(String map){
        this(map, BundledMaps.read(map));
    }

    SupportRun(String map, byte[] file){
        Vanilla.host(map);
        SaveFile save = READER.read(file);
        rules = LOADER.hostRules(save, Gamemode.SURVIVAL);
        MapLoader.Loaded loaded = LOADER.loadMap(save, HostedSystems.template(EPOCH, 0, 4096), rules);
        MapLoader.play(loaded);
        shard = loaded.world();
        assertEquals(Mathf.rand.seed0, shard.rand().getState(0), "random after hosting");
        assertEquals(lastId(), shard.ids().peek(), "next id after hosting");
        Time.setInternalTime(shard.timers().time());
        var core = state.rules.defaultTeam.core();
        coreX = core.tile.x;
        coreY = core.tile.y;
        VanillaPathfinder.take();
        VanillaPathfinder.freshRings();
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
        LogicPart.require(shard).logic().rand(new com.xpdustry.shard.common.math.Rand(mindustry.logic.GlobalVars.rand.seed0,
            mindustry.logic.GlobalVars.rand.seed1));
        events = shard.observe(1 << 14, List.of(), null);
        same("as hosted");
    }

    @SuppressWarnings("unchecked")
    static void freshProcess(){
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

    // ---- setting up, as a map editor would

    /** The core's stock of {@code item}, on both sides. */
    void items(String item, int amount){
        world.build(coreX, coreY).items.set(content.item(item), amount);
        shard.buildings().at(coreX, coreY).items.set(EPOCH.item(item).id(), amount);
    }

    /** A unit of the player team at (x, y), on both sides, carrying {@code amount} of {@code item} (or nothing). */
    void spawn(String type, float x, float y, String item, int amount){
        var ours = Unit.spawn(shard, EPOCH.unit(type), state.rules.defaultTeam.id, x, y, 0f);
        mindustry.gen.Unit theirs = content.unit(type).spawn(state.rules.defaultTeam, x, y);
        if(item != null){
            theirs.addItem(content.item(item), amount);
            ours.addItem(EPOCH.item(item).id(), amount);
        }
        assertEquals(theirs.id, ours.id(), "spawned " + type);
    }

    /** A plan the client keeps in its queue until the block stands. */
    void plan(String block, int x, int y, int rotation){
        plans.add(new Plan(block, x, y, rotation));
    }

    void at(int when, Runnable edit){
        edits.computeIfAbsent(when, k -> new ArrayList<>()).add(edit);
    }

    /** Places {@code block} at (x, y) for the player team on both sides, outside construction. */
    void place(String block, int x, int y, int rotation){
        world.tile(x, y).setBlock(content.block(block), state.rules.defaultTeam, rotation);
        Vanilla.runPosted();
        shard.buildings().setBlock(x, y, EPOCH.block(block), state.rules.defaultTeam.id, rotation, Cause.SERVER);
    }

    /** The building at (x, y) on both sides loses {@code fraction} of its health, as a hit would. */
    void damage(int x, int y, float fraction){
        var theirs = world.build(x, y);
        theirs.damage(theirs.maxHealth * fraction);
        Building ours = shard.buildings().at(x, y);
        ours.damage(ours.maxHealth * fraction);
    }

    mindustry.gen.Building vanilla(int x, int y){
        return world.build(x, y);
    }

    Building ours(int x, int y){
        return shard.buildings().at(x, y);
    }

    // ---- the client's item Calls, through vanilla's generated handlers and Shard's intents

    /** {@code transferInventory(player, build)}: the player's stack into the building at (x, y). */
    void transferInventory(int x, int y){
        var packet = new mindustry.gen.TransferInventoryCallPacket();
        packet.build = world.build(x, y);
        handle(packet);
        offer(new ItemIntents.TransferInventory(POLICY, Point2.pack(x, y)));
    }

    /** {@code requestItem(player, build, item, amount)}: items out of the building at (x, y) into the player's unit. */
    void requestItem(int x, int y, String item, int amount){
        var packet = new mindustry.gen.RequestItemCallPacket();
        packet.build = world.build(x, y);
        packet.item = content.item(item);
        packet.amount = amount;
        handle(packet);
        offer(new ItemIntents.RequestItem(POLICY, Point2.pack(x, y), EPOCH.item(item).id(), amount));
    }

    /** {@code dropItem(player, angle)}: the player's unit drops its stack. */
    void dropItem(float angle){
        var packet = new mindustry.gen.DropItemCallPacket();
        packet.angle = angle;
        handle(packet);
        offer(new ItemIntents.DropItem(angle));
    }

    /** A client packet as vanilla's server handles it, a refusal ({@code ValidateException}) included. */
    private static void handle(mindustry.net.Packet packet){
        try{
            packet.handleServer(Vanilla.connection());
        }catch(ValidateException ignored){
            // NetServer logs it and drops the packet, forward included
        }
    }

    // ---- a save in the middle of play

    /**
     * Vanilla's save of this world, loaded by both, as the server's {@code load} command does: vanilla's
     * {@code SaveIO.load}, then playing again; Shard's {@code MapLoader.load} into a hosted world. Each starts from a
     * generator seeded with 0, fresh ids and empty pools, since a loaded vanilla world's history is its JVM's, which
     * a fresh Shard world can't have (W2.18). The player, if any, isn't in the save: it stays out.
     */
    void saveAndLoad(){
        assertNull(vanillaPlayer, "a fixture with a player doesn't save");
        var file = new java.io.ByteArrayOutputStream();
        mindustry.io.SaveIO.write(file, new mindustry.io.SaveOptions());
        Mathf.rand.setSeed(0);
        setLastId(0);
        HazardPools.empty();
        mindustry.io.SaveIO.load(new java.io.ByteArrayInputStream(file.toByteArray()), world.context);
        state.set(mindustry.core.GameState.State.playing);
        Vanilla.runPosted();
        HazardPools.empty();
        SaveFile save = READER.read(file.toByteArray());
        MapLoader.Loaded loaded = LOADER.load(save, HostedSystems.template(EPOCH, 0, 4096), LOADER.saveRules(save));
        assertNull(loaded.state().unknownDraws, "draws Shard doesn't count");
        shard = loaded.world();
        shardPlayer = null;
        VanillaPathfinder.take();
        VanillaPathfinder.freshRings();
        freshProcess();
        physicsPinned = false;
        events = shard.observe(1 << 14, List.of(), null);
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

    /** Runs {@code frames} frames, comparing after each. */
    void run(int frames){
        int end = frame + frames;
        until(end + 1, () -> frame >= end);
    }

    private void step(){
        frame++;
        pinPhysics();
        VanillaPathfinder.beforeFrame();
        Construction sound = Construction.of(shard);
        var sent = new ArrayList<Vanilla.Capture.Sent>();
        Vanilla.net(Vanilla.NetMode.server, () -> {
            Vanilla.captured().clear();
            if(withPlayer && frame == 1) join();
            else if(vanillaPlayer != null && !vanillaPlayer.dead() && (frame - 1) % SNAPSHOT_FRAMES == 0) snapshot();
            for(Runnable edit : edits.getOrDefault(frame, List.of())) edit.run();
            Vanilla.runPosted();
            long played = sound.lastPlayed(), pitched = sound.lastPitch();
            shard.tick();
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

    private void join(){
        vanillaPlayer = mindustry.gen.Player.create();
        var con = Vanilla.connection();
        con.hasConnected = false;
        con.lastReceivedClientSnapshot = -1;
        con.lastReceivedClientTime = 0;
        con.rejectedRequests.clear();
        con.kicked = false;
        vanillaPlayer.con = con;
        con.player = vanillaPlayer;
        vanillaPlayer.name = "alpha";
        vanillaPlayer.team(state.rules.defaultTeam);
        NetServer.connectConfirm(vanillaPlayer);
        assertTrue(shard.submit(new HostInputs.Join(SESSION, 1, "alpha", 0xffd37fff, false), Cause.SERVER));
        offer(new HostInputs.Confirm(1));
    }

    /** The client's snapshot: its queue, the tile it mines and the pose the server has for its unit. */
    private void snapshot(){
        var unit = vanillaPlayer.unit();
        float x = unit.x, y = unit.y, rotation = unit.rotation;
        int id = ++snapshotId;
        var queue = new arc.struct.Queue<mindustry.entities.units.BuildPlan>();
        var ours = new ArrayList<BuildPlan>();
        for(Plan p : plans){
            queue.addLast(new mindustry.entities.units.BuildPlan(p.x, p.y, p.rotation, content.block(p.block)));
            ours.add(BuildPlan.place(p.x, p.y, p.rotation, EPOCH.block(p.block), null));
        }
        var mine = mining == null ? null : world.tile(mining[0], mining[1]);
        NetServer.clientSnapshot(vanillaPlayer, id, unit.id, false, x, y, x, y, rotation, 0f, 0f, 0f, mine, false, false, false, true, null, 0, queue,
            x, y, 1280f, 720f);
        offer(new Snapshot(POLICY, id, unit.id, false, x, y, x, y, rotation, 0f, 0f, 0f, mining == null ? -1 : Point2.pack(mining[0], mining[1]),
            false, false, false, true, -1, 0, ours, x, y, 1280f, 720f));
    }

    /**
     * Moves the player's unit to (x, y) on both sides at once, as a teleport its client then reports: the servers'
     * interpolation of a remote unit starts again from there.
     */
    void teleportPlayer(float x, float y){
        var unit = vanillaPlayer.unit();
        unit.set(x, y);
        unit.snapInterpolation();
        Unit ours = shardPlayer().unit();
        ours.set(x, y);
        RemoteSync.snap(ours);
    }

    private void offer(Intent intent){
        assertTrue(shard.inbox().offer(new IntentEnvelope(SESSION, 1, ++sequence, frame * 16_666_667L, intent)));
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

    // ---- Calls

    private List<String> shardCalls(List<WorldEvent> drained){
        var mapped = new ArrayList<WorldEvent>();
        var replies = new ArrayList<String>();
        var context = WorldWireContext.capture(shard);
        var out = new ArrayList<String>();
        for(WorldEvent event : drained){
            switch(event){
                // intents' Calls come out with the tick's events, in the order they were published
                case IntentCall.To to -> out.add(line("connection", to.packet(), context));
                case IntentCall.All all -> out.add(line("all", all.packet(), context));
                case IntentCall.AllExcept all -> out.add(line("except", all.packet(), context));
                case IntentCall.Team team -> out.add(line("team " + team.team(), team.packet(), context));
                default -> out.addAll(CallFrames.shard(shard, List.of(event)));
            }
        }
        return out;
    }

    private static String line(String target, Packet packet, WorldWireContext context){
        var body = new Writes();
        packet.write(body, true, context);
        return target + (Packets.TYPES.get(packet.packetId()).reliable() ? " tcp " : " udp ") + Packets.TYPES.get(packet.packetId()).name() + " "
            + Hex.of(body.toByteArray());
    }

    // ---- the comparison

    void same(String at){
        sameRandom(at);
        List<String> vanilla = vanillaState(), ours = shardState();
        for(int i = 0; i < Math.min(vanilla.size(), ours.size()); i++){
            if(!vanilla.get(i).equals(ours.get(i))) assertEquals(vanilla.get(i), ours.get(i), at);
        }
        assertEquals(String.join("\n", vanilla), String.join("\n", ours), at);
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
        out.add("next id " + lastId() + " time " + bits(Time.time));
        for(var tile : world.tiles){
            var b = tile.build;
            if(b == null || b.tile != tile) continue;
            var line = new StringBuilder(b.block.name + "@" + tile.x + "," + tile.y + " #" + b.id + " t" + b.team.id + " r" + b.rotation + " hp " + bits(b.health)
                + "/" + bits(b.maxHealth));
            if(b.items != null && !(b instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild)) line.append(" items ").append(items(id -> b.items.get(id)));
            if(b instanceof ConstructBuild site){
                line.append(" site ").append(site.current.name).append(" progress ").append(bits(site.progress)).append(" builder ")
                    .append(site.lastBuilder == null ? -1 : site.lastBuilder.id);
            }
            out.add(line.toString());
        }
        out.add("core items " + items(id -> world.build(coreX, coreY).items.get(id)));
        var plansLine = new StringBuilder("team plans");
        for(var plan : state.rules.defaultTeam.data().plans){
            plansLine.append(" [").append(plan.block.name).append(' ').append(plan.x).append(',').append(plan.y).append(" r").append(plan.rotation)
                .append(plan.removed ? " removed" : "").append(']');
        }
        out.add(plansLine.toString());
        out.add("player " + (vanillaPlayer == null ? "-" : bits(vanillaPlayer.x) + "," + bits(vanillaPlayer.y) + " unit " + (vanillaPlayer.dead() ? "-" : vanillaPlayer.unit().id)));
        for(mindustry.gen.Unit u : Groups.unit){
            Object target = u.controller() instanceof AIController ai ? get(TARGET, ai) : null;
            var line = new StringBuilder(unit(u.id, u.type.name, u.team.id, u.x, u.y, u.rotation, u.vel.x, u.vel.y, u.health, u.elevation, u.dead, nameOf(target)));
            line.append(" stack ").append(u.stack.item.name).append('x').append(u.stack.amount).append(" mine ")
                .append(u.mineTile == null ? "-" : u.mineTile.x + "," + u.mineTile.y).append(' ').append(bits(u.mineTimer));
            for(mindustry.entities.units.WeaponMount m : u.mounts){
                line.append(" [").append(bits(m.reload)).append(' ').append(bits(m.rotation)).append(' ').append(m.totalShots).append(' ').append(bits(m.aimX))
                    .append(',').append(bits(m.aimY)).append(' ').append(m.shoot).append(' ').append(nameOf(m.target)).append(']');
            }
            if(u.canBuild()){
                line.append(" counter ").append(bits((Float)field(u, "buildCounter"))).append(" updating ").append(u.updateBuilding).append(" plans");
                for(var plan : u.plans()){
                    line.append(" [").append(plan.breaking ? "break" : plan.block.name + " r" + plan.rotation).append(' ').append(plan.x).append(',').append(plan.y)
                        .append(' ').append(bits(plan.progress)).append(plan.initialized ? " init" : "").append(plan.stuck ? " stuck" : "").append(']');
                }
            }
            line.append(" ai ").append(vanillaAi(u.controller()));
            out.add(line.toString());
        }
        for(Bullet b : Groups.bullet){
            out.add("bullet " + b.id + " t" + b.type.id + " (" + bits(b.x) + ", " + bits(b.y) + ") v(" + bits(b.vel.x) + ", " + bits(b.vel.y) + ") time "
                + bits(b.time) + " dmg " + bits(b.damage));
        }
        return out;
    }

    /** The AI a command runs, or the controller itself, with its timers and state. */
    private static String vanillaAi(mindustry.entities.units.UnitController controller){
        Object ai = controller;
        var out = new StringBuilder();
        if(controller instanceof CommandAI command){
            out.append("command ").append(command.command == null ? "-" : command.command.name).append(' ');
            ai = get(COMMAND_CONTROLLER, command);
            if(ai == null) return out.append("default").toString();
        }
        out.append(ai.getClass().getSimpleName());
        if(ai instanceof AIController a){
            for(float time : ((arc.util.Interval)field(a, "timer")).getTimes()) out.append(' ').append(bits(time));
        }
        switch(ai){
            case MinerAI m -> out.append(" mining ").append(m.mining).append(" item ").append(m.targetItem == null ? "-" : m.targetItem.name).append(" ore ")
                .append(m.ore == null ? "-" : m.ore.x + "," + m.ore.y);
            case BuilderAI b -> out.append(" following ").append(nameOf(b.following)).append(" assist ").append(nameOf(b.assistFollowing)).append(" enemy ")
                .append(nameOf(field(b, "enemy"))).append(" last ").append(b.lastPlan == null ? "-" : b.lastPlan.x + "," + b.lastPlan.y + (b.lastPlan.removed ? " removed" : ""))
                .append(" retreat ").append(bits((Float)field(b, "retreatTimer"))).append(" period ").append(bits(b.rebuildPeriod)).append(" only ").append(b.onlyAssist);
            case RepairAI r -> out.append(" damaged ").append(nameOf(field(r, "damagedTarget"))).append(" avoid ").append(nameOf(field(r, "avoid")))
                .append(" retreat ").append(bits((Float)field(r, "retreatTimer")));
            default -> {}
        }
        return out.toString();
    }

    List<String> shardState(){
        var out = new ArrayList<String>();
        out.add("next id " + shard.ids().peek() + " time " + bits(shard.timers().time()));
        var tiles = shard.tiles();
        for(int pos = 0; pos < tiles.size(); pos++){
            Building b = shard.buildings().at(pos);
            if(b == null || b.tile() != pos) continue;
            var line = new StringBuilder(b.block().name() + "@" + b.tileX() + "," + b.tileY() + " #" + b.id() + " t" + b.team + " r" + b.rotation + " hp "
                + bits(b.health) + "/" + bits(b.maxHealth));
            if(b.items != null && !b.block().name().startsWith("core-")) line.append(" items ").append(items(id -> b.items.get(id)));
            if(b instanceof com.xpdustry.shard.sim.build.ConstructBuild site){
                line.append(" site ").append(EPOCH.block(site.current).name()).append(" progress ").append(bits(site.progress)).append(" builder ")
                    .append(site.lastBuilder == null ? -1 : site.lastBuilder.id());
            }
            out.add(line.toString());
        }
        Building core = shard.buildings().at(coreX, coreY);
        out.add("core items " + items(id -> core.items.get(id)));
        var plansLine = new StringBuilder("team plans");
        for(BlockPlan plan : Teams.of(shard).get(rules.defaultTeam).plans()){
            plansLine.append(" [").append(EPOCH.block(plan.block).name()).append(' ').append(plan.x).append(',').append(plan.y).append(" r").append(plan.rotation)
                .append(plan.removed ? " removed" : "").append(']');
        }
        out.add(plansLine.toString());
        out.add("player " + (shardPlayer() == null ? "-" : bits(shardPlayer.x) + "," + bits(shardPlayer.y) + " unit " + (shardPlayer.dead() ? "-" : shardPlayer.unit().id())));
        for(int i = 0; i < shard.units().size(); i++){
            Unit u = shard.units().get(i);
            Entity target = u.controller instanceof AiController ai ? ai.target : null;
            var line = new StringBuilder(unit(u.id(), u.type().name(), u.team, u.x, u.y, u.rotation, u.vel.x, u.vel.y, u.health, u.elevation, u.dead, nameOf(target)));
            line.append(" stack ").append(EPOCH.item(u.stackItem).name()).append('x').append(u.stackAmount).append(" mine ")
                .append(u.mineTile == -1 ? "-" : Point2.x(u.mineTile) + "," + Point2.y(u.mineTile)).append(' ').append(bits(u.mineTimer));
            for(WeaponMount m : u.mounts){
                line.append(" [").append(bits(m.reload)).append(' ').append(bits(m.rotation)).append(' ').append(m.totalShots).append(' ').append(bits(m.aimX))
                    .append(',').append(bits(m.aimY)).append(' ').append(m.shoot).append(' ').append(nameOf(m.target)).append(']');
            }
            if(com.xpdustry.shard.sim.build.Builder.canBuild(u)){
                line.append(" counter ").append(bits(u.buildCounter)).append(" updating ").append(u.updateBuilding).append(" plans");
                for(int p = 0; p < u.plans.size(); p++){
                    BuildPlan plan = u.plans.get(p);
                    line.append(" [").append(plan.breaking ? "break" : EPOCH.block(plan.block).name() + " r" + plan.rotation).append(' ').append(plan.x).append(',')
                        .append(plan.y).append(' ').append(bits(plan.progress)).append(plan.initialized ? " init" : "").append(plan.stuck ? " stuck" : "").append(']');
                }
            }
            line.append(" ai ").append(shardAi(u.controller));
            out.add(line.toString());
        }
        Bullets t = shard.bullets();
        for(int r = 0; r < t.size(); r++){
            out.add("bullet " + t.id[r] + " t" + t.type[r] + " (" + bits(t.x[r]) + ", " + bits(t.y[r]) + ") v(" + bits(t.velX[r]) + ", " + bits(t.velY[r])
                + ") time " + bits(t.time[r]) + " dmg " + bits(t.damage[r]));
        }
        return out;
    }

    private static String shardAi(com.xpdustry.shard.sim.unit.UnitController controller){
        Object ai = controller;
        var out = new StringBuilder();
        if(controller instanceof CommandAi command){
            List<String> commands = EPOCH.unitCommands();
            out.append("command ").append(command.command < 0 ? "-" : commands.get(command.command)).append(' ');
            ai = command.commandController;
            if(ai == null) return out.append("default").toString();
        }
        out.append(switch(ai){
            case MinerAi ignored -> "MinerAI";
            case BuilderAi ignored -> "BuilderAI";
            case RepairAi ignored -> "RepairAI";
            case PlayerControl ignored -> "Player";
            default -> ai.getClass().getSimpleName();
        });
        if(ai instanceof AiController a){
            for(float time : a.timers) out.append(' ').append(bits(time));
        }
        switch(ai){
            case MinerAi m -> out.append(" mining ").append(m.mining).append(" item ").append(m.targetItem == -1 ? "-" : EPOCH.item(m.targetItem).name()).append(" ore ")
                .append(m.ore == -1 ? "-" : Point2.x(m.ore) + "," + Point2.y(m.ore));
            case BuilderAi b -> out.append(" following ").append(nameOf(b.following)).append(" assist ").append(nameOf(b.assistFollowing)).append(" enemy ")
                .append(nameOf(b.enemy)).append(" last ").append(b.lastPlan == null ? "-" : b.lastPlan.x + "," + b.lastPlan.y + (b.lastPlan.removed ? " removed" : ""))
                .append(" retreat ").append(bits(b.retreatTimer)).append(" period ").append(bits(b.rebuildPeriod)).append(" only ").append(b.onlyAssist);
            case RepairAi r -> out.append(" damaged ").append(nameOf(r.damagedTarget)).append(" avoid ").append(nameOf(r.avoid)).append(" retreat ").append(bits(r.retreatTimer));
            default -> {}
        }
        return out.toString();
    }

    Player shardPlayer(){
        if(shardPlayer == null){
            Roster roster = Roster.of(shard);
            shardPlayer = roster == null ? null : roster.player(SESSION);
        }
        return shardPlayer;
    }

    private static String unit(int id, String type, int team, float x, float y, float rotation, float vx, float vy, float health, float elevation,
                               boolean dead, String target){
        return "unit #" + id + " " + type + " t" + team + " at " + bits(x) + "," + bits(y) + " rot " + bits(rotation) + " v " + bits(vx) + "," + bits(vy)
            + " hp " + bits(health) + " el " + bits(elevation) + (dead ? " dead" : "") + " target " + target;
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
            return open(EntityGroup.class.getDeclaredField("lastId")).getInt(null);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    private static void setLastId(int value){
        try{
            open(EntityGroup.class.getDeclaredField("lastId")).setInt(null, value);
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
