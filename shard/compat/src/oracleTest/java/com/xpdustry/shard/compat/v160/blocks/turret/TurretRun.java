package com.xpdustry.shard.compat.v160.blocks.turret;

import arc.math.*;
import arc.util.pooling.*;
import com.xpdustry.shard.compat.v160.blocks.HazardStates;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.bullet.Bullets;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.world.*;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.blocks.defense.turrets.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One turret fixture in a vanilla world and a Shard one, built in step: turrets placed as a map editor places them,
 * ammo, liquids and power, and enemy units that walk a straight line ({@link #walker}) or stand still. {@link #run}
 * steps both and compares after every frame:
 * <ul>
 *   <li>the random generator's state, the entity id counter and {@code Time.time};</li>
 *   <li>every turret: aim, reload, warmup, heat, charge, ammo entries, target, aim point, queued bullets, shots, barrel,
 *   shooting flags, efficiency, liquids and health;</li>
 *   <li>every unit: added, position, velocity, rotation, health;</li>
 *   <li>the live bullets in group order: id, type, team, position, velocity, time, damage;</li>
 *   <li>every fire and puddle with its timers, and the tile maps ({@link HazardStates});</li>
 * </ul>
 * and every turret's save chunk at the end. Ids are relative to each side's counter before the fixture, which the
 * constructor aligns, as {@code CombatOracle} does.
 */
final class TurretRun implements AutoCloseable{
    static final Epoch EPOCH = Epoch.vanilla();
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();

    final World shard;
    final int vanillaBase, shardBase;
    final List<int[]> turrets = new ArrayList<>();
    final List<mindustry.gen.Unit> vanillaUnits = new ArrayList<>();
    final List<Unit> shardUnits = new ArrayList<>();
    private final Pool<mindustry.gen.Bullet> pool;
    int frame;
    /** Edits made before a frame, on both sides: every {@code period} frames, from the first. */
    private final List<Map.Entry<Integer, Runnable>> every = new ArrayList<>();
    /** Shots fired and units killed over the run, for the fixtures' "it happened" checks. */
    int shots, deaths;

    TurretRun(int size){
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
        var rules = shard.part(WorldRules.class).rules;
        rules.disableUnitCap = true;
        rules.waves = false;
        shardBase = shard.ids().peek();
        shard.rand().setState(Mathf.rand.seed0, Mathf.rand.seed1);
        setStatic(EntityGroup.class, "lastId", shardBase);
        setStatic(mindustry.entities.Lightning.class, "lastSeed", 0);
        // Vanilla.reset zeroes Time.time but not Arc's raw clock, which the next frame copies back
        arc.util.Time.setInternalTime(0);
        vanillaBase = lastId();
    }

    @Override
    public void close(){
        pool.clear();
    }

    // ---------------------------------------------------------------- building the fixture

    /** Places {@code block} for {@code team} at (x, y) on both sides, as a map editor does. */
    void place(String block, int x, int y, Team team){
        world.tile(x, y).setBlock(content.block(block), team, 0);
        shard.buildings().setBlock(x, y, EPOCH.block(block), team.id, 0, Cause.SERVER);
        if(world.build(x, y) instanceof Turret.TurretBuild) turrets.add(new int[]{x, y});
        sameRandom("after placing " + block);
    }

    void items(int x, int y, String item, int amount){
        for(int i = 0; i < amount; i++){
            world.build(x, y).handleItem(null, content.item(item));
            shard.buildings().at(x, y).handleItem(null, EPOCH.item(item).id());
        }
    }

    void liquid(int x, int y, String liquid, float amount){
        world.build(x, y).handleLiquid(world.build(x, y), content.liquid(liquid), amount);
        Building b = shard.buildings().at(x, y);
        b.handleLiquid(b, EPOCH.liquid(liquid).id(), amount);
    }

    /** {@code Fires.create} at (x, y) on both sides. */
    void ignite(int x, int y){
        Fires.create(world.tile(x, y));
        com.xpdustry.shard.sim.fire.Fires.create(shard, shard.tiles().pos(x, y));
        sameRandom("after igniting " + x + "," + y);
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

    /** Runs {@code edit} (on both sides) before every {@code period}-th frame, the first included. */
    void every(int period, Runnable edit){
        every.add(Map.entry(period, edit));
    }

    // ---------------------------------------------------------------- running

    /** Steps both worlds {@code frames} times or until {@code done}, comparing after each frame; then the save chunks. */
    void run(int frames, BooleanSupplier done){
        for(int i = 0; i < frames; i++){
            for(var edit : every) if(frame % edit.getKey() == 0) edit.getValue().run();
            int deadBefore = dead();
            Vanilla.frame();
            shard.tick();
            frame++;
            compare("frame " + frame);
            deaths += dead() - deadBefore;
            if(done.getAsBoolean()) break;
        }
        System.out.println(turrets.isEmpty() ? "no turret" : world.build(turrets.getFirst()[0], turrets.getFirst()[1]).block.name
            + ": " + frame + " frames, " + shots + " shots, " + deaths + " of " + vanillaUnits.size() + " killed, next id "
            + (shard.ids().peek() - shardBase));
        chunks();
    }

    /**
     * As {@link #run}, until the first turret fires: that frame compares the turrets and the id counter only, since the
     * bullet's own creation and hits are its kind's (for a kind another row ports). No save chunks.
     */
    void runUntilShot(int frames){
        for(int i = 0; i < frames; i++){
            for(var edit : every) if(frame % edit.getKey() == 0) edit.getValue().run();
            Vanilla.frame();
            shard.tick();
            frame++;
            String at = "frame " + frame;
            boolean fired = false;
            for(int[] t : turrets) fired |= ((Turret.TurretBuild)world.build(t[0], t[1])).totalShots > 0;
            if(!fired){
                compare(at);
                continue;
            }
            assertEquals(lastId() - vanillaBase, shard.ids().peek() - shardBase, at + ": the entity id counter");
            for(int[] t : turrets){
                assertEquals(vanillaTurret(t[0], t[1]), shardTurret(t[0], t[1]), at + ": turret at " + t[0] + "," + t[1]);
            }
            shots = 1;
            return;
        }
        fail("no shot within " + frames + " frames");
    }

    private int dead(){
        int count = 0;
        for(mindustry.gen.Unit unit : vanillaUnits) if(!unit.isAdded()) count++;
        return count;
    }

    boolean allDead(){
        return dead() == vanillaUnits.size() && mindustry.gen.Groups.bullet.isEmpty();
    }

    void compare(String at){
        sameRandom(at);
        assertEquals(lastId() - vanillaBase, shard.ids().peek() - shardBase, at + ": the entity id counter");
        same(at + ": Time.time", arc.util.Time.time, shard.timers().time());
        for(int[] t : turrets){
            assertEquals(vanillaTurret(t[0], t[1]), shardTurret(t[0], t[1]), at + ": turret at " + t[0] + "," + t[1]);
        }
        for(int i = 0; i < vanillaUnits.size(); i++){
            assertEquals(vanillaUnit(vanillaUnits.get(i)), shardUnit(shardUnits.get(i)), at + ": unit " + i);
        }
        assertEquals(vanillaBullets(), shardBullets(), at + ": live bullets");
        assertEquals(HazardStates.vanilla(), HazardStates.shard(shard), at + ": fires and puddles");
        int shotsNow = 0;
        for(int[] t : turrets) shotsNow += ((Turret.TurretBuild)world.build(t[0], t[1])).totalShots;
        shots = shotsNow;
    }

    void sameRandom(String at){
        assertEquals(Mathf.rand.seed0, shard.rand().getState(0), at + ": random state");
        assertEquals(Mathf.rand.seed1, shard.rand().getState(1), at + ": random state");
    }

    static void same(String what, float vanilla, float shard){
        if(Float.floatToIntBits(vanilla) != Float.floatToIntBits(shard)) fail(what + ": " + shard + ", vanilla " + vanilla);
    }

    // ---------------------------------------------------------------- state lines

    String vanillaTurret(int x, int y){
        var b = (Turret.TurretBuild)world.build(x, y);
        var ammo = new StringBuilder();
        for(Turret.AmmoEntry entry : b.ammo){
            ammo.append(entry instanceof mindustry.world.blocks.defense.turrets.ItemTurret.ItemEntry item ? item.item.id : -1).append('x').append(entry.amount).append(' ');
        }
        String target = switch(b.target){
            case null -> "-";
            case mindustry.gen.Unit u -> "unit " + (u.id - vanillaBase);
            case mindustry.gen.Building t -> "build " + t.tileX() + "," + t.tileY();
            case Fire f -> "fire " + f.tileX() + "," + f.tileY();
            default -> b.target.toString();
        };
        return turret(b.rotation, b.reloadCounter, b.shootWarmup, b.heat, b.charge, ammo.toString(), b.totalAmmo, target, b.targetPos.x,
            b.targetPos.y, b.queuedBullets, b.totalShots, b.barrelCounter, b.isShooting, b.wasShooting, b.efficiency, b.health,
            b.liquids == null ? "" : b.liquids.current().id + ":" + b.liquids.currentAmount(), b.power == null ? 0f : b.power.status);
    }

    String shardTurret(int x, int y){
        var b = (TurretBuild)shard.buildings().at(x, y);
        var ammo = new StringBuilder();
        for(int i = 0; i < b.ammoSize; i++) ammo.append(b.ammoIds[i]).append('x').append(b.ammoAmounts[i]).append(' ');
        String target;
        if(b.target instanceof Unit u) target = "unit " + (u.id() - shardBase);
        else if(b.target instanceof Building t) target = "build " + t.tileX() + "," + t.tileY();
        else if(b.targetFire != -1){
            // vanilla's Fire.tileX(): where the kept object stands now
            var at = b.targetFirePosition();
            target = "fire " + Math.round(at.x / 8f) + "," + Math.round(at.y / 8f);
        }
        else target = "-";
        return turret(b.turretRotation, b.reloadCounter, b.shootWarmup, b.heat, b.charge, ammo.toString(), b.totalAmmo, target, b.targetX,
            b.targetY, b.queuedBullets, b.totalShots, b.barrelCounter, b.isShooting, b.wasShooting, b.efficiency, b.health,
            b.liquids == null ? "" : b.liquids.current() + ":" + b.liquids.currentAmount(), b.power == null ? 0f : b.power.status);
    }

    private static String turret(float rotation, float reload, float warmup, float heat, float charge, String ammo, int total, String target,
                                 float tx, float ty, int queued, int shots, int barrel, boolean shooting, boolean wasShooting,
                                 float efficiency, float health, String liquid, float power){
        return "rot " + rotation + " reload " + reload + " warmup " + warmup + " heat " + heat + " charge " + charge + " ammo [" + ammo
            + "] total " + total + " target " + target + " aim (" + tx + ", " + ty + ") queued " + queued + " shots " + shots
            + " barrel " + barrel + " shooting " + shooting + "/" + wasShooting + " eff " + efficiency + " health " + health
            + " liquid " + liquid + " power " + power;
    }

    private String vanillaUnit(mindustry.gen.Unit u){
        if(!u.isAdded()) return "gone";
        return unit(u.x, u.y, u.vel.x, u.vel.y, u.rotation, u.health);
    }

    private String shardUnit(Unit u){
        if(!u.isAdded()) return "gone";
        return unit(u.x, u.y, u.vel.x, u.vel.y, u.rotation, u.health);
    }

    private static String unit(float x, float y, float vx, float vy, float rotation, float health){
        return "(" + x + ", " + y + ") v(" + vx + ", " + vy + ") rot " + rotation + " health " + health;
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

    void chunks(){
        var current = ContentHeader.of(EPOCH).mapping(EPOCH);
        for(int[] t : turrets){
            mindustry.gen.Building expected = world.build(t[0], t[1]);
            assertEquals(hex(vanillaChunk(expected)), hex(CODECS.chunk(shard.buildings().at(t[0], t[1]), EPOCH, current)),
                expected.block.name + " at (" + t[0] + ", " + t[1] + "): save chunk");
        }
    }

    /** {@code SaveVersion.writeChunk}'s body for a building: its version byte, then {@code writeAll}. */
    static byte[] vanillaChunk(mindustry.gen.Building build){
        var bytes = new java.io.ByteArrayOutputStream();
        var writes = new arc.util.io.Writes(new java.io.DataOutputStream(bytes));
        writes.b(build.version());
        build.writeAll(writes);
        return bytes.toByteArray();
    }

    static String hex(byte[] bytes){
        return HexFormat.of().formatHex(bytes);
    }

    // ---------------------------------------------------------------- vanilla's counters

    static int lastId(){
        try{
            Field field = EntityGroup.class.getDeclaredField("lastId");
            field.setAccessible(true);
            return field.getInt(null);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    private static void setStatic(Class<?> owner, String name, int value){
        try{
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.setInt(null, value);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }
}
