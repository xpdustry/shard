package com.xpdustry.shard.compat.v160.blocks.defense;

import arc.math.*;
import arc.struct.*;
import arc.util.pooling.*;
import com.xpdustry.shard.compat.v160.blocks.HazardStates;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.sync.CallFrames;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.defense.DefenseEvent;
import com.xpdustry.shard.sim.blocks.sandbox.SandboxEvent;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.bullet.Bullets;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.Checkpoint;
import com.xpdustry.shard.sim.persist.Compression;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import com.xpdustry.shard.sim.rules.Rules;
import com.xpdustry.shard.wire.v160_7.Packets;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.world.blocks.campaign.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.defense.turrets.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One defence or sandbox fixture in a vanilla world and a Shard one, built and edited in step. {@link #run} steps both
 * and compares after every frame, exactly:
 * <ul>
 *   <li>the random generator's state, the entity id counter and {@code Time.time};</li>
 *   <li>every building placed ({@link #place}): health, efficiency, timers, items, liquids, power, and its kind's fields
 *   (a wall's {@code hit}, a door's state and chain, a shield, a dummy's counters and unit, a pad's state);</li>
 *   <li>every unit in group order, the dummies' included: added, position, velocity, rotation, health, team, armor
 *   and hit size;</li>
 *   <li>the live bullets in group order, the fires and puddles ({@link HazardStates}), and the launch payloads;</li>
 *   <li>the frame's Calls, byte for byte ({@link CallFrames}): configs, auto door toggles, dummies' spawns, landing
 *   pads, destroyed buildings;</li>
 * </ul>
 * and every placed building's save chunk at the end. {@link #reload} then loads vanilla's save of the fixture on both
 * sides, to go on from there. Ids are relative to each side's counter before the fixture, which the constructor
 * aligns, as {@code TurretRun} does.
 */
final class DefenseRun implements AutoCloseable{
    static final Epoch EPOCH = Epoch.vanilla();
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final MsavReader READER = new MsavReader(Worlds.EPOCH);
    static final MapLoader LOADER = new MapLoader(Worlds.EPOCH, CODECS);
    static final Set<Integer> CALLS = Set.of(Packets.TileConfig.ID, Packets.AutoDoorToggle.ID, Packets.UnitTetherBlockSpawned.ID,
        Packets.LandingPadLanded.ID, Packets.BuildDestroyed.ID);

    World shard;
    int vanillaBase, shardBase;
    private Observer events;
    /** Every building placed, as {x, y}: its state is compared every frame and its chunk at the end. */
    final List<int[]> placed = new ArrayList<>();
    final List<mindustry.gen.Unit> vanillaUnits = new ArrayList<>();
    final List<Unit> shardUnits = new ArrayList<>();
    private final Pool<mindustry.gen.Bullet> pool;
    private final Map<Integer, List<Runnable>> at = new TreeMap<>();
    private final List<Map.Entry<Integer, Runnable>> every = new ArrayList<>();
    /** Calls sent so far, by packet name, and lightning bolts made, for the fixtures' "it happened" checks. */
    final Map<String, Integer> calls = new TreeMap<>();
    int frame;

    DefenseRun(int size){
        Vanilla.emptyWorld(size, size, content.block("stone"));
        HazardPools.empty();
        state.rules.disableUnitCap = true;
        state.rules.waves = false;
        mindustry.gen.Groups.updatePooling();
        mindustry.gen.Bullet.create();
        pool = Pools.get(mindustry.gen.Bullet.class, () -> null);
        pool.clear();
        shard = new World(WorldConfig.of(EPOCH, size, size, 1).withSystems(Systems.builtIn())
            .withKinds(KindRegistry.builtIn().withPassthrough()));
        shard.buildings().beginLoad();
        for(int pos = 0; pos < shard.tiles().size(); pos++) shard.tiles().floor(pos, EPOCH.block("stone").id());
        shard.buildings().endLoad(false);
        var rules = rules();
        rules.disableUnitCap = true;
        rules.waves = false;
        shardBase = shard.ids().peek();
        shard.rand().setState(Mathf.rand.seed0, Mathf.rand.seed1);
        setStatic(EntityGroup.class, "lastId", shardBase);
        setStatic(mindustry.entities.Lightning.class, "lastSeed", 0);
        // Vanilla.reset zeroes Time.time but not Arc's raw clock, which the next frame copies back
        arc.util.Time.setInternalTime(0);
        vanillaBase = lastId();
        events = observe();
    }

    @Override
    public void close(){
        pool.clear();
    }

    Rules rules(){
        return shard.part(WorldRules.class).rules;
    }

    private Observer observe(){
        return shard.observe(4096, List.of(BuildingEvent.class, DefenseEvent.class, SandboxEvent.class), null);
    }

    // ---------------------------------------------------------------- building the fixture

    /** Places {@code block} for {@code team} at (x, y) on both sides, as a map editor does, and watches it. */
    void place(String block, int x, int y, Team team){
        place(block, x, y, team, 0);
    }

    void place(String block, int x, int y, Team team, int rotation){
        world.tile(x, y).setBlock(content.block(block), team, rotation);
        shard.buildings().setBlock(x, y, EPOCH.block(block), team.id, rotation, Cause.SERVER);
        if(world.build(x, y) != null) placed.add(new int[]{x, y});
        sameRandom("after placing " + block);
    }

    /** The team's {@code cheat} rule on both sides: its buildings need nothing to run. */
    void cheat(Team team){
        state.rules.teams.get(team).cheat = true;
        rules().teams.get(team.id).cheat = true;
    }

    /** The team's {@code blockDamageMultiplier} on both sides: its turrets' bullets hit that much harder. */
    void blockDamage(Team team, float multiplier){
        state.rules.teams.get(team).blockDamageMultiplier = multiplier;
        rules().teams.get(team.id).blockDamageMultiplier = multiplier;
    }

    /** Items handed to a building, one by one, while it stands on both sides. */
    void items(int x, int y, String item, int amount){
        if(world.build(x, y) == null || shard.buildings().at(x, y) == null) return;
        for(int i = 0; i < amount; i++){
            world.build(x, y).handleItem(null, content.item(item));
            shard.buildings().at(x, y).handleItem(null, EPOCH.item(item).id());
        }
    }

    /** Liquid put straight into a building, as a map editor's. */
    void liquid(int x, int y, String liquid, float amount){
        world.build(x, y).liquids.add(content.liquid(liquid), amount);
        shard.buildings().at(x, y).liquids.add(EPOCH.liquid(liquid).id(), amount);
    }

    /** A config from the server ({@code configureAny}): vanilla's {@code Call.tileConfig(null, build, value)}. */
    void configure(int x, int y, Object vanilla, Object shard){
        world.build(x, y).configureAny(vanilla);
        Vanilla.runPosted();
        this.shard.buildings().at(x, y).configureAny(shard);
    }

    /**
     * A player's own config, as the server applies it when its {@code tileConfig} arrives: the handler runs with the
     * player's unit, and the client already ran it, so the Call is the input's forward, not the building's.
     */
    void playerConfigure(int x, int y, Object vanilla, Object shard, int unit){
        world.build(x, y).configured(vanillaUnits.get(unit), vanilla);
        Vanilla.runPosted();
        Building building = this.shard.buildings().at(x, y);
        this.shard.buildings().configureForwarded(building, shardUnits.get(unit), shard, Cause.SERVER);
    }

    /**
     * A processor running {@code code}, linked to each {@code (x, y)} in {@code links} as a player links. The configs
     * are frame 0's edits, so their Calls are that frame's on both sides.
     */
    void processor(String block, int x, int y, Team team, String code, int... links){
        place(block, x, y, team);
        at(0, () -> {
            configure(x, y, mindustry.world.blocks.logic.LogicBlock.compress(code, new Seq<>()), LogicConfig.compress(code, List.of()));
            for(int i = 0; i < links.length; i += 2){
                configure(x, y, arc.math.geom.Point2.pack(links[i], links[i + 1]), com.xpdustry.shard.common.geom.Point2.pack(links[i], links[i + 1]));
            }
        });
    }

    /** Items added straight to a building's module, as a map editor's. */
    void give(int x, int y, String item, int amount){
        world.build(x, y).items.add(content.item(item), amount);
        shard.buildings().at(x, y).items.add(EPOCH.item(item).id(), amount);
    }

    /** An enemy of {@code team} at (x, y) walking toward (tx, ty), or standing if they are the same point. */
    void walker(String type, Team team, float x, float y, float tx, float ty){
        mindustry.gen.Unit v = content.unit(type).spawn(team, x, y);
        Unit s = Unit.spawn(shard, EPOCH.unit(type), team.id, x, y, 0f);
        v.controller(new Walkers.VanillaWalker(tx, ty));
        s.controller(new Walkers.ShardWalker(tx, ty));
        vanillaUnits.add(v);
        shardUnits.add(s);
        sameRandom("after spawning " + type);
    }

    /**
     * A unit that stands where it spawns: vanilla's walker already at its goal, and Shard's {@link NoAi}, which a fork
     * and a checkpoint carry (the test's walker has no checkpoint form).
     */
    void stand(String type, Team team, float x, float y){
        mindustry.gen.Unit v = content.unit(type).spawn(team, x, y);
        Unit s = Unit.spawn(shard, EPOCH.unit(type), team.id, x, y, 0f);
        v.controller(new Walkers.VanillaWalker(x, y));
        s.controller(new NoAi());
        vanillaUnits.add(v);
        shardUnits.add(s);
        sameRandom("after spawning " + type);
    }

    /** Runs {@code edit} (on both sides) before frame {@code frame} (the first is 0). */
    void at(int frame, Runnable edit){
        at.computeIfAbsent(frame, k -> new ArrayList<>()).add(edit);
    }

    /** Runs {@code edit} (on both sides) before every {@code period}-th frame, the first included. */
    void every(int period, Runnable edit){
        every.add(Map.entry(period, edit));
    }

    // ---------------------------------------------------------------- running

    /** Steps both worlds {@code frames} times or until {@code done}, comparing after each frame; then the save chunks. */
    void run(int frames, BooleanSupplier done){
        for(int i = 0; i < frames; i++){
            var sent = new ArrayList<Vanilla.Capture.Sent>();
            Vanilla.net(Vanilla.NetMode.server, () -> {
                Vanilla.captured().clear();
                for(var edit : every) if(frame % edit.getKey() == 0) edit.getValue().run();
                for(Runnable edit : at.getOrDefault(frame, List.of())) edit.run();
                Vanilla.runPosted();
                Vanilla.frame();
                sent.addAll(Vanilla.captured());
            });
            shard.tick();
            frame++;
            List<String> expected = CallFrames.vanilla(sent, CALLS);
            CallFrames.same(expected, CallFrames.shard(shard, events.drain()), "Calls of frame " + frame);
            for(String line : expected) calls.merge(line.split(" ")[2], 1, Integer::sum);
            compare("frame " + frame);
            if(done.getAsBoolean()) break;
        }
        System.out.println(frame + " frames, calls " + calls + ", lightning " + lightning() + ", next id " + (shard.ids().peek() - shardBase));
        chunks("save chunk");
    }

    /**
     * Vanilla's save of the fixture, loaded by both sides, as a server's {@code load} command does, with the per-JVM
     * counters a fresh server would have; the units lists follow their units by id. Shard's loaded world starts from
     * seed 0 and entity id 0, so vanilla's random and id counter start there too before its load: every draw and id
     * either side's load takes shows in the random and the counter compared right after it, and every frame on.
     */
    void reload(){
        var file = new ByteArrayOutputStream();
        SaveIO.write(file, new SaveOptions());
        setStatic(EntityGroup.class, "lastId", 0);
        Mathf.rand.setSeed(0);
        SaveIO.load(new ByteArrayInputStream(file.toByteArray()), world.context);
        state.set(mindustry.core.GameState.State.playing);
        Vanilla.runPosted();
        HazardPools.empty();
        setStatic(mindustry.entities.Lightning.class, "lastSeed", 0);
        SaveFile save = READER.read(file.toByteArray());
        shard = LOADER.load(save, Worlds.config(0), LOADER.saveRules(save)).world();
        vanillaBase = shardBase = 0;
        sameRandom("loaded");
        assertEquals(lastId(), shard.ids().peek(), "the entity id counter after loading");
        var vanillaIds = vanillaUnits.stream().map(u -> u.id).toList();
        vanillaUnits.clear();
        for(int id : vanillaIds) vanillaUnits.add(mindustry.gen.Groups.unit.getByID(id));
        follow(shard);
        chunks("save chunk after loading vanilla's save");
        compare("loaded");
    }

    /** {@code tile.remove()} on both sides: the tile becomes air and its neighbours rebuild their proximity. */
    void remove(int x, int y){
        world.tile(x, y).remove();
        Vanilla.runPosted();
        shard.buildings().setBlock(x, y, EPOCH.block("air"), 0, 0, Cause.SERVER);
        sameRandom("after removing (" + x + ", " + y + ")");
    }

    /** Shard carries on as a fork of its world; vanilla has none, so the fork must go on exactly as the world would. */
    void forkShard(){
        follow(shard.fork());
        compare("forked");
    }

    /** Shard carries on from a checkpoint of its world, encoded and restored. */
    void restoreShard(){
        follow(Checkpoint.decode(Checkpoint.capture(shard).encode(Compression.FAST)).restore(shard.config()));
        compare("restored");
    }

    /** Swaps in {@code next}, which has the same entities: the units list and the Call observer follow it. */
    private void follow(World next){
        var ids = shardUnits.stream().map(Unit::id).toList();
        shard = next;
        shardUnits.clear();
        for(int id : ids) shardUnits.add((Unit)EntityGroups.of(shard).byId(id));
        events = observe();
    }

    void compare(String at){
        sameRandom(at);
        assertEquals(lastId() - vanillaBase, shard.ids().peek() - shardBase, at + ": the entity id counter");
        same(at + ": Time.time", arc.util.Time.time, shard.timers().time());
        assertEquals(String.join("\n", vanillaState()), String.join("\n", shardState()), at);
        assertEquals(vanillaBullets(), shardBullets(), at + ": live bullets");
        assertEquals(HazardStates.vanilla(), HazardStates.shard(shard), at + ": fires and puddles");
    }

    void sameRandom(String at){
        assertEquals(Mathf.rand.seed0, shard.rand().getState(0), at + ": random state");
        assertEquals(Mathf.rand.seed1, shard.rand().getState(1), at + ": random state");
    }

    static void same(String what, float vanilla, float shard){
        if(Float.floatToIntBits(vanilla) != Float.floatToIntBits(shard)) fail(what + ": " + shard + ", vanilla " + vanilla);
    }

    /** Lightning bolts made since the fixture began: vanilla's seed counter. */
    static int lightning(){
        return getStatic(mindustry.entities.Lightning.class, "lastSeed");
    }

    // ---------------------------------------------------------------- state lines

    List<String> vanillaState(){
        var out = new ArrayList<String>();
        for(int[] p : placed){
            mindustry.gen.Building b = world.build(p[0], p[1]);
            if(b == null || b.tile.x != p[0] || b.tile.y != p[1]){
                out.add(p[0] + "," + p[1] + " gone");
                continue;
            }
            var line = new StringBuilder(p[0] + "," + p[1] + " " + b.block.name + " team " + b.team.id + " health " + b.health + " enabled "
                + b.enabled + " eff " + b.efficiency + " timers " + Arrays.toString(b.timer.getTimes()));
            if(b.items != null) line.append(" items ").append(b.items.total()).append(b.items.total() > 0 ? "x" + b.items.first().id : "");
            if(b.liquids != null) line.append(" liquid ").append(b.liquids.current().id).append(':').append(b.liquids.currentAmount());
            if(b.power != null) line.append(" power ").append(b.power.status);
            switch(b){
                case ShieldWall.ShieldWallBuild w -> line.append(" hit ").append((float)get(w, Wall.WallBuild.class, "hit")).append(" shield ").append(w.shield)
                    .append(" radius ").append(w.shieldRadius).append(" break ").append(w.breakTimer);
                case Wall.WallBuild w -> line.append(" hit ").append((float)get(w, Wall.WallBuild.class, "hit"));
                case Door.DoorBuild d -> {
                    line.append(" open ").append(d.open).append(" chain");
                    for(var c : d.chained) line.append(' ').append(c.tile.x).append(',').append(c.tile.y);
                }
                case AutoDoor.AutoDoorBuild d -> line.append(" open ").append(d.open);
                case TargetDummy.TargetDummyBuild d -> line.append(" unit ").append(d.unit == null ? -1 : d.unit.id - vanillaBase).append(" read ")
                    .append(d.readUnitId).append(" total ").append(d.total).append(" reset ").append(d.reset).append(" time ").append(d.time)
                    .append(" dps ").append(d.dps).append(" display ").append(d.totalDisplay).append('/').append(d.timeDisplay).append('/')
                    .append(d.hitsDisplay).append(" hits ").append(d.hits).append(" config ").append(Arrays.toString(d.config()));
                case LandingPad.LandingPadBuild l -> line.append(" config ").append(l.config == null ? -1 : l.config.id).append(" priority ")
                    .append(l.priority).append(" cooldown ").append(l.cooldown).append(" arriving ").append(l.arriving == null ? -1 : l.arriving.id)
                    .append(" timer ").append(l.arrivingTimer).append(" removed ").append(l.liquidRemoved);
                case LaunchPad.LaunchPadBuild l -> line.append(" counter ").append(l.launchCounter);
                case Accelerator.AcceleratorBuild a -> line.append(" heat ").append(a.heat).append(" status ").append(a.statusLerp)
                    .append(" progress ").append(a.progress).append(" time ").append(a.time).append(" launch ").append(a.launchHeat);
                default -> {
                }
            }
            out.add(line.toString());
        }
        for(mindustry.gen.Unit u : mindustry.gen.Groups.unit){
            out.add("unit " + (u.id - vanillaBase) + " " + u.type.name + " team " + u.team.id + " (" + u.x + ", " + u.y + ") v(" + u.vel.x + ", "
                + u.vel.y + ") rot " + u.rotation + " health " + u.health + " armor " + u.armor + " size " + u.hitSize + " elevation " + u.elevation);
        }
        for(Entityc e : mindustry.gen.Groups.all){
            if(e instanceof mindustry.gen.LaunchPayload p){
                out.add("payload " + (p.id - vanillaBase) + " team " + p.team.id + " (" + p.x + ", " + p.y + ") time " + p.time + "/" + p.lifetime
                    + " in " + p.in.getTimes()[0]);
            }
        }
        return out;
    }

    List<String> shardState(){
        var out = new ArrayList<String>();
        var tiles = shard.tiles();
        for(int[] p : placed){
            Building b = shard.buildings().at(p[0], p[1]);
            if(b == null || b.tileX() != p[0] || b.tileY() != p[1]){
                out.add(p[0] + "," + p[1] + " gone");
                continue;
            }
            var line = new StringBuilder(p[0] + "," + p[1] + " " + b.block().name() + " team " + b.team + " health " + b.health + " enabled "
                + b.enabled + " eff " + b.efficiency + " timers " + Arrays.toString(b.timer));
            if(b.items != null) line.append(" items ").append(b.items.total()).append(b.items.total() > 0 ? "x" + b.items.first() : "");
            if(b.liquids != null) line.append(" liquid ").append(b.liquids.current()).append(':').append(b.liquids.currentAmount());
            if(b.power != null) line.append(" power ").append(b.power.status);
            switch(b){
                case com.xpdustry.shard.sim.blocks.defense.ShieldWall w -> line.append(" hit ").append(w.hit).append(" shield ").append(w.shield).append(" radius ").append(w.shieldRadius)
                    .append(" break ").append(w.breakTimer);
                case com.xpdustry.shard.sim.blocks.defense.Wall w -> line.append(" hit ").append(w.hit);
                case com.xpdustry.shard.sim.blocks.defense.Thruster w -> line.append(" hit ").append(w.hit);
                case com.xpdustry.shard.sim.blocks.defense.Door d -> {
                    line.append(" open ").append(d.open).append(" chain");
                    for(var c : d.chained()) line.append(' ').append(tiles.x(c.tile())).append(',').append(tiles.y(c.tile()));
                }
                case com.xpdustry.shard.sim.blocks.defense.AutoDoor d -> line.append(" open ").append(d.open);
                case com.xpdustry.shard.sim.blocks.sandbox.TargetDummy d -> line.append(" unit ").append(d.unit == null ? -1 : d.unit.id() - shardBase)
                    .append(" read ").append(d.readUnitId).append(" total ").append(d.total).append(" reset ").append(d.reset).append(" time ")
                    .append(d.time).append(" dps ").append(d.dps).append(" display ").append(d.totalDisplay).append('/').append(d.timeDisplay)
                    .append('/').append(d.hitsDisplay).append(" hits ").append(d.hits).append(" config ").append(Arrays.toString(d.config().ints()));
                case com.xpdustry.shard.sim.blocks.sandbox.LandingPad l -> line.append(" config ").append(l.config).append(" priority ")
                    .append(l.priority).append(" cooldown ").append(l.cooldown).append(" arriving ").append(l.arriving).append(" timer ")
                    .append(l.arrivingTimer).append(" removed ").append(l.liquidRemoved);
                case com.xpdustry.shard.sim.blocks.sandbox.LaunchPad l -> line.append(" counter ").append(l.launchCounter);
                case com.xpdustry.shard.sim.blocks.sandbox.Accelerator a -> line.append(" heat ").append(a.heat).append(" status ")
                    .append(a.statusLerp).append(" progress ").append(a.progress).append(" time ").append(a.time).append(" launch ").append(a.launchHeat);
                default -> {
                }
            }
            out.add(line.toString());
        }
        for(int i = 0; i < shard.units().size(); i++){
            Unit u = shard.units().get(i);
            out.add("unit " + (u.id() - shardBase) + " " + u.type().name() + " team " + u.team + " (" + u.x + ", " + u.y + ") v(" + u.vel.x + ", "
                + u.vel.y + ") rot " + u.rotation + " health " + u.health + " armor " + u.armor + " size " + u.hitSize + " elevation " + u.elevation);
        }
        EntityGroups groups = EntityGroups.of(shard);
        for(int i = 0; i < groups.all().size(); i++){
            if(groups.all().get(i) instanceof com.xpdustry.shard.sim.blocks.sandbox.LaunchPayload p){
                out.add("payload " + (p.id() - shardBase) + " team " + p.team + " (" + p.x + ", " + p.y + ") time " + p.time + "/" + p.lifetime
                    + " in " + p.in);
            }
        }
        return out;
    }

    List<String> vanillaBullets(){
        var out = new ArrayList<String>();
        for(mindustry.gen.Bullet b : mindustry.gen.Groups.bullet){
            out.add(bullet(b.id - vanillaBase, b.type.id, b.team.id, b.x, b.y, b.vel.x, b.vel.y, b.time, b.lifetime, b.damage));
        }
        return out;
    }

    List<String> shardBullets(){
        var out = new ArrayList<String>();
        Bullets t = shard.bullets();
        for(int r = 0; r < t.size(); r++){
            out.add(bullet(t.id[r] - shardBase, t.type[r], t.team[r], t.x[r], t.y[r], t.velX[r], t.velY[r], t.time[r], t.lifetime[r], t.damage[r]));
        }
        return out;
    }

    private static String bullet(int id, int type, int team, float x, float y, float vx, float vy, float time, float lifetime, float damage){
        return id + " t" + type + " team" + team + " (" + x + ", " + y + ") v(" + vx + ", " + vy + ") time " + time + "/" + lifetime
            + " dmg " + damage;
    }

    // ---------------------------------------------------------------- save chunks

    void chunks(String what){
        var current = ContentHeader.of(EPOCH).mapping(EPOCH);
        for(int[] p : placed){
            mindustry.gen.Building expected = world.build(p[0], p[1]);
            if(expected == null || expected.tile.x != p[0] || expected.tile.y != p[1]) continue;
            assertEquals(hex(vanillaChunk(expected)), hex(CODECS.chunk(shard.buildings().at(p[0], p[1]), EPOCH, current)),
                expected.block.name + " at (" + p[0] + ", " + p[1] + "): " + what);
        }
    }

    /** {@code SaveVersion.writeChunk}'s body for a building: its version byte, then {@code writeAll}. */
    static byte[] vanillaChunk(mindustry.gen.Building build){
        var bytes = new ByteArrayOutputStream();
        var writes = new arc.util.io.Writes(new DataOutputStream(bytes));
        writes.b(build.version());
        build.writeAll(writes);
        return bytes.toByteArray();
    }

    static String hex(byte[] bytes){
        return HexFormat.of().formatHex(bytes);
    }

    // ---------------------------------------------------------------- vanilla's counters and hidden fields

    static int lastId(){
        return getStatic(EntityGroup.class, "lastId");
    }

    static int getStatic(Class<?> owner, String name){
        try{
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(null);
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

    static Object get(Object target, Class<?> owner, String name){
        try{
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }
}
