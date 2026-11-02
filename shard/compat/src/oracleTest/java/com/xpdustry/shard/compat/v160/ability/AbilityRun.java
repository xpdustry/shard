package com.xpdustry.shard.compat.v160.ability;

import arc.math.*;
import arc.util.pooling.*;
import com.xpdustry.shard.compat.v160.blocks.HazardStates;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.sync.CallFrames;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.bullet.Bullets;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.rules.Rules;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.unit.ability.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import com.xpdustry.shard.wire.v160_7.*;
import mindustry.entities.*;
import mindustry.entities.abilities.Ability;
import mindustry.game.*;
import mindustry.io.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One ability fixture in a vanilla world and a Shard one, built in step (after W3.5's {@code DefenseRun}). {@link #run}
 * steps both and compares after every frame, exactly:
 * <ul>
 *   <li>the random generator's state, the entity id counter and {@code Time.time};</li>
 *   <li>every building placed: health, and whether its healing is suppressed;</li>
 *   <li>every unit in group order: position, velocity, rotation, health, shield, death, statuses, mounts' reloads, and
 *   each ability's {@code data} and kept state (timers, a shield's radius, a repair field's sums…);</li>
 *   <li>every unit's {@code writeSync} and save body ({@code write}), byte for byte;</li>
 *   <li>the live bullets in group order, the fires and puddles ({@link HazardStates});</li>
 *   <li>the frame's unit Calls, byte for byte ({@link CallFrames}): deaths, safe deaths, spawns.</li>
 * </ul>
 * {@link #reload} then loads vanilla's save of the fixture on both sides, to go on from there: each unit's abilities
 * keep only their {@code data}, as vanilla's {@code readAbilities} placeholders do.
 */
final class AbilityRun implements AutoCloseable{
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final MsavReader READER = new MsavReader(Worlds.EPOCH);
    static final MapLoader LOADER = new MapLoader(Worlds.EPOCH, CODECS);
    static final Set<Integer> ALL_CALLS = Set.of(Packets.UnitDeath.ID, Packets.UnitDestroy.ID, Packets.UnitSafeDeath.ID, Packets.UnitSpawn.ID,
        Packets.UnitDespawn.ID, Packets.BuildDestroyed.ID);

    /** Shard's content: vanilla's, or an overlay on it whose vanilla side the fixture sets up itself. */
    final Epoch epoch;
    private final EntityProjector projector;
    World shard;
    int vanillaBase, shardBase;
    private Observer events;
    final List<int[]> placed = new ArrayList<>();
    final List<mindustry.gen.Unit> vanillaUnits = new ArrayList<>();
    final List<Unit> shardUnits = new ArrayList<>();
    private final Pool<mindustry.gen.Bullet> pool;
    private final Map<Integer, List<Runnable>> at = new TreeMap<>();
    /** Calls sent so far, by packet name, for the fixtures' "it happened" checks. */
    final Map<String, Integer> calls = new TreeMap<>();
    /** Watches run after every compared frame, on the vanilla side: what the fixture wants to see happen. */
    final List<Runnable> watches = new ArrayList<>();
    /** The packets compared byte for byte; a fixture may leave some out ({@link #inexact}). */
    private final Set<Integer> exactCalls = new TreeSet<>(ALL_CALLS);
    int frame;

    AbilityRun(int size, String floor){
        this(size, floor, Epoch.vanilla());
    }

    AbilityRun(int size, String floor, Epoch epoch){
        this.epoch = epoch;
        projector = new EntityProjector(epoch, CODECS);
        Vanilla.emptyWorld(size, size, content.block(floor));
        HazardPools.empty();
        state.rules.disableUnitCap = true;
        state.rules.waves = false;
        mindustry.gen.Groups.updatePooling();
        mindustry.gen.Bullet.create();
        pool = Pools.get(mindustry.gen.Bullet.class, () -> null);
        pool.clear();
        shard = new World(WorldConfig.of(epoch, size, size, 1).withSystems(Systems.builtIn())
            .withKinds(KindRegistry.builtIn().withPassthrough()));
        shard.buildings().beginLoad();
        for(int pos = 0; pos < shard.tiles().size(); pos++) shard.tiles().floor(pos, epoch.block(floor).id());
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
        return shard.observe(4096, List.of(UnitEvent.class, CombatEvent.class, BuildingEvent.class), null);
    }

    // ---------------------------------------------------------------- building the fixture

    /** A floor under (x, y) on both sides, before anything stands there. */
    void floor(int x, int y, String floor){
        world.tile(x, y).setFloor(content.block(floor).asFloor());
        shard.tiles().floor(shard.tiles().pos(x, y), epoch.block(floor).id());
    }

    /** Places {@code block} for {@code team} at (x, y) on both sides, as a map editor does, and watches it. */
    void place(String block, int x, int y, Team team){
        world.tile(x, y).setBlock(content.block(block), team, 0);
        shard.buildings().setBlock(x, y, epoch.block(block), team.id, 0, Cause.SERVER);
        if(world.build(x, y) != null) placed.add(new int[]{x, y});
        sameRandom("after placing " + block);
    }

    /** The team's {@code cheat} rule on both sides: its buildings need nothing to run. */
    void cheat(Team team){
        state.rules.teams.get(team).cheat = true;
        rules().teams.get(team.id).cheat = true;
    }

    /** Sets a placed building's health on both sides. */
    void buildingHealth(int x, int y, float health){
        world.build(x, y).health = health;
        shard.buildings().at(x, y).health = health;
    }

    /**
     * A unit of {@code type} for {@code team} at (x, y), facing {@code rotation}, run by a {@link Fighters} script that
     * walks to (tx, ty) and fires at the first living unit of {@code target} in the fixture's list, if any is within
     * {@code range}, else holds fire. Returns its index.
     */
    int fighter(String type, Team team, float x, float y, float rotation, float tx, float ty, Team target, float range){
        mindustry.gen.Unit v = content.unit(type).spawn(team, x, y, rotation);
        Unit s = Unit.spawn(shard, epoch.unit(type), team.id, x, y, rotation);
        int index = vanillaUnits.size();
        vanillaUnits.add(v);
        shardUnits.add(s);
        v.controller(new Fighters.VanillaFighter(vanillaUnits, tx, ty, target == null ? -1 : target.id, range));
        s.controller(new Fighters.ShardFighter(shardUnits, tx, ty, target == null ? -1 : target.id, range));
        sameRandom("after spawning " + type);
        return index;
    }

    /** A unit that stands where it is and never shoots. */
    int stander(String type, Team team, float x, float y, float rotation){
        return fighter(type, team, x, y, rotation, x, y, null, 0f);
    }

    /** Sets unit {@code index}'s health on both sides. */
    void health(int index, float health){
        vanillaUnits.get(index).health = health;
        shardUnits.get(index).health = health;
    }

    /**
     * Leaves packet {@code id} out of the byte comparison: {@code unitSpawn}'s body is the unit as it is after the tick
     * on Shard's side ({@code CombatCallMapping}), as it was at the spawn on vanilla's.
     */
    void inexact(int id){
        exactCalls.remove(id);
    }

    /** Runs {@code edit} (on both sides) before frame {@code frame} (the first is 0). */
    void at(int frame, Runnable edit){
        at.computeIfAbsent(frame, k -> new ArrayList<>()).add(edit);
    }

    // ---------------------------------------------------------------- running

    /** Steps both worlds {@code frames} times, comparing after each frame. */
    void run(int frames){
        for(int i = 0; i < frames; i++){
            var sent = new ArrayList<Vanilla.Capture.Sent>();
            Vanilla.net(Vanilla.NetMode.server, () -> {
                Vanilla.captured().clear();
                for(Runnable edit : at.getOrDefault(frame, List.of())) edit.run();
                Vanilla.runPosted();
                Vanilla.frame();
                sent.addAll(Vanilla.captured());
            });
            shard.tick();
            frame++;
            List<String> expected = CallFrames.vanilla(sent, exactCalls);
            var made = new ArrayList<>(CallFrames.shard(shard, events.drain()));
            for(int id : ALL_CALLS) if(!exactCalls.contains(id)) made.removeIf(line -> line.split(" ")[2].equals(Packets.TYPES.get(id).name()));
            CallFrames.same(expected, made, "Calls of frame " + frame);
            for(String line : expected) calls.merge(line.split(" ")[2], 1, Integer::sum);
            compare("frame " + frame);
            for(Runnable watch : watches) watch.run();
        }
        System.out.println(frame + " frames, calls " + calls + ", next id " + (shard.ids().peek() - shardBase));
    }

    /**
     * Vanilla's save of the fixture, loaded by both sides, as a server's {@code load} command does, with the per-JVM
     * counters a fresh server would have; the unit lists follow their units by id, and keep their scripts.
     */
    void reload(){
        var file = new ByteArrayOutputStream();
        SaveIO.write(file, new SaveOptions());
        var vanillaIds = vanillaUnits.stream().map(u -> u.id).toList();
        var scripts = new ArrayList<Object[]>();
        for(int i = 0; i < vanillaUnits.size(); i++) scripts.add(new Object[]{vanillaUnits.get(i).controller(), shardUnits.get(i).controller});
        setStatic(EntityGroup.class, "lastId", 0);
        SaveIO.load(new ByteArrayInputStream(file.toByteArray()), world.context);
        state.set(mindustry.core.GameState.State.playing);
        Vanilla.runPosted();
        HazardPools.empty();
        // and an empty bullet pool, as a fresh server has: the old world's bullets are freed now, their resets' ids undone
        int loadedId = lastId();
        mindustry.gen.Groups.updatePooling();
        pool.clear();
        setStatic(EntityGroup.class, "lastId", loadedId);
        setStatic(mindustry.entities.Lightning.class, "lastSeed", 0);
        SaveFile save = READER.read(file.toByteArray());
        shard = LOADER.load(save, Worlds.config(0), LOADER.saveRules(save)).world();
        shard.rand().setState(Mathf.rand.seed0, Mathf.rand.seed1);
        vanillaBase = shardBase = 0;
        assertEquals(lastId(), shard.ids().peek(), "the entity id counter after loading");
        vanillaUnits.clear();
        shardUnits.clear();
        for(int i = 0; i < vanillaIds.size(); i++){
            mindustry.gen.Unit v = mindustry.gen.Groups.unit.getByID(vanillaIds.get(i));
            Unit s = (Unit)EntityGroups.of(shard).byId(vanillaIds.get(i));
            assertEquals(v != null, s != null, "unit " + vanillaIds.get(i) + " after loading");
            vanillaUnits.add(v);
            shardUnits.add(s);
            if(v == null) continue;
            v.controller((mindustry.entities.units.UnitController)scripts.get(i)[0]);
            s.controller((UnitController)scripts.get(i)[1]);
        }
        // the scripts' lists are the run's, which now hold the loaded units
        events = observe();
        compare("loaded");
    }

    void compare(String at){
        sameRandom(at);
        assertEquals(lastId() - vanillaBase, shard.ids().peek() - shardBase, at + ": the entity id counter");
        same(at + ": Time.time", arc.util.Time.time, shard.timers().time());
        assertEquals(String.join("\n", vanillaState()), String.join("\n", shardState()), at);
        assertEquals(vanillaBullets(), shardBullets(), at + ": live bullets");
        assertEquals(HazardStates.vanilla(), HazardStates.shard(shard), at + ": fires and puddles");
        bytes(at);
    }

    /** Every live unit's {@code writeSync} and save body equal vanilla's. */
    void bytes(String at){
        var context = WorldWireContext.capture(shard);
        for(mindustry.gen.Unit v : mindustry.gen.Groups.unit){
            Unit s = (Unit)EntityGroups.of(shard).byId(v.id - vanillaBase + shardBase);
            assertNotNull(s, at + ": unit " + v.id);
            int classId = v.classId() & 0xff;
            UnitProjection projection = projector.unit(s);
            String what = at + " " + v.type.name + "#" + (v.id - vanillaBase);
            compare(what + " sync", Golden.bytes(v::writeSync), out -> EntityClasses.writeSync(out, classId, projection, context));
            compare(what + " save", Golden.bytes(v::write), out -> EntityClasses.writeSave(out, classId, projection, context));
        }
    }

    static void compare(String what, byte[] expected, Consumer<com.xpdustry.shard.common.io.Writes> writer){
        var out = new com.xpdustry.shard.common.io.Writes();
        writer.accept(out);
        byte[] actual = out.toByteArray();
        assertArrayEquals(expected, actual, () -> what + "\nvanilla " + Hex.of(expected) + "\nshard   " + Hex.of(actual));
    }

    void sameRandom(String at){
        assertEquals(Mathf.rand.seed0, shard.rand().getState(0), at + ": random state");
        assertEquals(Mathf.rand.seed1, shard.rand().getState(1), at + ": random state");
    }

    static void same(String what, float vanilla, float shard){
        if(Float.floatToIntBits(vanilla) != Float.floatToIntBits(shard)) fail(what + ": " + shard + ", vanilla " + vanilla);
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
            out.add(p[0] + "," + p[1] + " " + b.block.name + " team " + b.team.id + " health " + b.health + " suppressed " + b.isHealSuppressed());
        }
        for(mindustry.gen.Unit u : mindustry.gen.Groups.unit){
            var line = new StringBuilder(unit(u.id - vanillaBase, u.type.name, u.team.id, u.x, u.y, u.vel.x, u.vel.y, u.rotation, u.health,
                u.shield, u.dead, u.elevation));
            for(var entry : statuses(u)) line.append(" status ").append(entry.effect.id).append(':').append(entry.time);
            for(var mount : u.mounts) line.append(" reload ").append(mount.reload);
            for(Ability a : u.abilities) line.append(" | ").append(vanillaAbility(a));
            out.add(line.toString());
        }
        return out;
    }

    List<String> shardState(){
        var out = new ArrayList<String>();
        for(int[] p : placed){
            Building b = shard.buildings().at(p[0], p[1]);
            if(b == null || b.tileX() != p[0] || b.tileY() != p[1]){
                out.add(p[0] + "," + p[1] + " gone");
                continue;
            }
            out.add(p[0] + "," + p[1] + " " + b.block().name() + " team " + b.team + " health " + b.health + " suppressed " + b.isHealSuppressed());
        }
        for(int i = 0; i < shard.units().size(); i++){
            Unit u = shard.units().get(i);
            var line = new StringBuilder(unit(u.id() - shardBase, u.type().name(), u.team, u.x, u.y, u.vel.x, u.vel.y, u.rotation, u.health,
                u.shield, u.dead, u.elevation));
            for(int s = 0; s < u.statuses.size(); s++){
                StatusEntry entry = u.statuses.get(s);
                line.append(" status ").append(entry.effect).append(':').append(entry.time);
            }
            for(WeaponMount mount : u.mounts) line.append(" reload ").append(mount.reload);
            for(UnitAbility a : u.abilities) line.append(" | ").append(shardAbility(a));
            out.add(line.toString());
        }
        return out;
    }

    private static String unit(int id, String type, int team, float x, float y, float vx, float vy, float rotation, float health, float shield,
                               boolean dead, float elevation){
        return "unit " + id + " " + type + " team " + team + " (" + x + ", " + y + ") v(" + vx + ", " + vy + ") rot " + rotation + " health "
            + health + " shield " + shield + " dead " + dead + " elevation " + elevation;
    }

    /** The state vanilla's copy keeps that the server reads: {@code data} and, per kind, the fields Shard keeps. */
    static String vanillaAbility(Ability a){
        var line = new StringBuilder("data " + a.data);
        // content declares most abilities as anonymous subclasses ({@code new RepairFieldAbility(){{...}}})
        List<String> kept = List.of();
        for(Class<?> c = a.getClass(); c != null && kept.isEmpty(); c = c.getSuperclass()) kept = KEPT.getOrDefault(c.getSimpleName(), List.of());
        for(String field : kept) line.append(' ').append(field).append(' ').append(get(a, field));
        return line.toString();
    }

    static String shardAbility(UnitAbility a){
        var line = new StringBuilder("data " + a.data);
        switch(a){
            case ForceFieldAbility.State s -> line.append(" radiusScale ").append(s.radiusScale).append(" wasBroken ").append(s.wasBroken);
            case RepairFieldAbility.State s -> line.append(" healTimer ").append(s.healTimer).append(" downTimer ").append(s.downTimer)
                .append(" healthMissing ").append(s.healthMissing).append(" healthChange ").append(s.healthChange);
            case Timed s -> line.append(" timer ").append(s.timer);
            case ArmorPlateAbility.State s -> line.append(" warmup ").append(s.warmup);
            case MoveLightningAbility.State s -> line.append(" side ").append(s.side);
            default -> {
            }
        }
        return line.toString();
    }

    /** By vanilla class: the fields {@link #shardAbility} prints, in its order. */
    static final Map<String, List<String>> KEPT = Map.of(
        "ForceFieldAbility", List.of("radiusScale", "wasBroken"),
        "RepairFieldAbility", List.of("healTimer", "downTimer", "healthMissing", "healthChange"),
        "StatusFieldAbility", List.of("timer"),
        "ShieldRegenFieldAbility", List.of("timer"),
        "EnergyFieldAbility", List.of("timer"),
        "SuppressionFieldAbility", List.of("timer"),
        "UnitSpawnAbility", List.of("timer"),
        "ArmorPlateAbility", List.of("warmup"),
        "MoveLightningAbility", List.of("side"));

    @SuppressWarnings("unchecked")
    static arc.struct.Seq<mindustry.entities.units.StatusEntry> statuses(mindustry.gen.Unit unit){
        return (arc.struct.Seq<mindustry.entities.units.StatusEntry>)field(unit, "statuses");
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

    // ---------------------------------------------------------------- vanilla's counters and hidden fields

    static int lastId(){
        try{
            Field field = EntityGroup.class.getDeclaredField("lastId");
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

    /** A field by name, declared by the object's class or a superclass. */
    static Object field(Object target, String name){
        try{
            for(Class<?> c = target.getClass(); c != null; c = c.getSuperclass()){
                for(Field field : c.getDeclaredFields()){
                    if(field.getName().equals(name) && !Modifier.isStatic(field.getModifiers())){
                        field.setAccessible(true);
                        return field.get(target);
                    }
                }
            }
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
        throw new AssertionError(target.getClass().getName() + " has no field " + name);
    }

    static Object get(Object target, String name){
        return field(target, name);
    }
}
