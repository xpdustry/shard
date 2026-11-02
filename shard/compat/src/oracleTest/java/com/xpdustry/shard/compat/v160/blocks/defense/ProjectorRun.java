package com.xpdustry.shard.compat.v160.blocks.defense;

import arc.math.*;
import arc.util.pooling.*;
import com.xpdustry.shard.compat.v160.blocks.HazardStates;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.transport.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.bullet.Bullet;
import com.xpdustry.shard.sim.bullet.Bullets;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.world.*;
import mindustry.entities.*;
import mindustry.game.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One projector or support-turret fixture in a vanilla world and a Shard one, built in step: blocks placed as a map
 * editor places them, items, liquids, stored power and damage set by hand, and units that walk a straight line or
 * stand. {@link #run} steps both and compares after every frame:
 * <ul>
 *   <li>the random generator's state, the entity id counter and {@code Time.time};</li>
 *   <li>every building, by tile: health, time scale and its duration, efficiencies, items, liquids, power, timers,
 *   conveyor slots, and the fields of each W3.6 kind ({@link #FIELDS}, vanilla's names first);</li>
 *   <li>every unit: added, position, velocity, rotation, health;</li>
 *   <li>the live bullets in group order, and the fires and puddles ({@link HazardStates});</li>
 * </ul>
 * and the save chunk of every W3.6 building at the end. Ids are relative to each side's counter before the fixture,
 * which the constructor aligns, as {@code TurretRun} does.
 */
final class ProjectorRun implements AutoCloseable{
    static final Epoch EPOCH = Epoch.vanilla();
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();

    /** Per vanilla building class: its compared fields, as vanilla name and Shard name. */
    static final Map<String, String[][]> FIELDS = Map.ofEntries(
        Map.entry("MendBuild", pairs("heat", "charge", "phaseHeat", "smoothEfficiency")),
        Map.entry("OverdriveBuild", pairs("heat", "charge", "phaseHeat", "smoothEfficiency", "useProgress")),
        Map.entry("ForceBuild", pairs("broken", "buildup", "radscl", "hit", "warmup", "phaseHeat")),
        Map.entry("RegenProjectorBuild", pairs("targets", "lastChange", "warmup", "totalTime", "optionalTimer", "anyTargets", "didRegen")),
        Map.entry("ShockwaveTowerBuild", pairs("reloadCounter", "heat")),
        Map.entry("RadarBuild", pairs("progress", "lastRadius", "smoothEfficiency", "totalProgress")),
        Map.entry("BaseShieldBuild", pairs("broken", "hit", "smoothRadius")),
        Map.entry("PointDefenseBuild", new String[][]{{"rotation", "turretRotation"}, {"reloadCounter", "reloadCounter"},
            {"activationTimer", "activationTimer"}, {"target", "target"}}),
        Map.entry("TractorBeamBuild", new String[][]{{"rotation", "turretRotation"}, {"activationTimer", "activationTimer"}, {"target", "target"},
            {"lastX", "lastX"}, {"lastY", "lastY"}, {"strength", "strength"}, {"any", "any"}, {"coolantMultiplier", "coolantMultiplier"}}),
        Map.entry("RepairPointBuild", new String[][]{{"rotation", "turretRotation"}, {"target", "target"}, {"strength", "strength"}}),
        Map.entry("RepairTowerBuild", pairs("refresh", "warmup", "totalProgress", "targets")));

    final World shard;
    final int vanillaBase, shardBase;
    final List<mindustry.gen.Unit> vanillaUnits = new ArrayList<>();
    final List<Unit> shardUnits = new ArrayList<>();
    private final Pool<mindustry.gen.Bullet> pool;
    private final Map<Integer, List<Runnable>> at = new TreeMap<>();
    private final List<Map.Entry<Integer, Runnable>> every = new ArrayList<>();
    /** Things that happened over the run, by name, for the fixtures' "it happened" checks. */
    final Map<String, Integer> seen = new TreeMap<>();
    private final List<Map.Entry<String, BooleanSupplier>> watches = new ArrayList<>();
    int frame;

    ProjectorRun(int size){
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
        com.xpdustry.shard.sim.rules.Rules rules = shard.part(WorldRules.class).rules;
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

    private static String[][] pairs(String... names){
        var out = new String[names.length][];
        for(int i = 0; i < names.length; i++) out[i] = new String[]{names[i], names[i]};
        return out;
    }

    // ---------------------------------------------------------------- building the fixture

    void place(String block, int x, int y, Team team){
        place(block, x, y, 0, team);
    }

    /** Places {@code block} for {@code team} at (x, y) on both sides, as a map editor does. */
    void place(String block, int x, int y, int rotation, Team team){
        world.tile(x, y).setBlock(content.block(block), team, rotation);
        shard.buildings().setBlock(x, y, EPOCH.block(block), team.id, rotation, Cause.SERVER);
        sameRandom("after placing " + block);
    }

    /** {@code configureAny(item)}: an item source's or sorter's item. */
    void configure(int x, int y, String item){
        world.build(x, y).configureAny(content.item(item));
        Vanilla.runPosted();
        shard.buildings().at(x, y).configureAny(ContentRef.item(EPOCH.item(item).id()));
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

    /** A battery's (or any buffered block's) stored fraction. */
    void charge(int x, int y, float status){
        world.build(x, y).power.status = status;
        shard.buildings().at(x, y).power.status = status;
    }

    /** Gives the building at (x, y) {@code health} of maximum and current health on both sides, so a fixture's target lives. */
    void toughen(int x, int y, float health){
        var v = world.build(x, y);
        v.maxHealth = v.health = health;
        Building b = shard.buildings().at(x, y);
        b.maxHealth = b.health = health;
    }

    /** {@code damage(amount)} on both sides. */
    void damage(int x, int y, float amount){
        world.build(x, y).damage(amount);
        shard.buildings().at(x, y).damage(amount);
        sameRandom("after damaging " + x + "," + y);
    }

    /** A unit of {@code team} at (x, y) walking toward (tx, ty), or standing if they are the same point. */
    int walker(String type, Team team, float x, float y, float tx, float ty){
        mindustry.gen.Unit v = content.unit(type).spawn(team, x, y);
        Unit s = Unit.spawn(shard, EPOCH.unit(type), team.id, x, y, 0f);
        v.controller(new Walkers.VanillaWalker(tx, ty));
        s.controller(new Walkers.ShardWalker(tx, ty));
        vanillaUnits.add(v);
        shardUnits.add(s);
        sameRandom("after spawning " + type);
        return vanillaUnits.size() - 1;
    }

    /** Sets unit {@code i}'s health on both sides. */
    void unitHealth(int i, float health){
        vanillaUnits.get(i).health = health;
        shardUnits.get(i).health = health;
    }

    /** Runs {@code edit} (on both sides) before frame {@code at}. */
    void at(int frame, Runnable edit){
        at.computeIfAbsent(frame, k -> new ArrayList<>()).add(edit);
    }

    /** Runs {@code edit} (on both sides) before every {@code period}-th frame, the first included. */
    void every(int period, Runnable edit){
        every.add(Map.entry(period, edit));
    }

    /** Counts the frames after which {@code condition} (read on vanilla's side) holds, under {@code name}. */
    void watch(String name, BooleanSupplier condition){
        watches.add(Map.entry(name, condition));
    }

    int seen(String name){
        return seen.getOrDefault(name, 0);
    }

    // ---------------------------------------------------------------- running

    /** Steps both worlds {@code frames} times or until {@code done}, comparing after each frame; then the save chunks. */
    void run(int frames, BooleanSupplier done){
        for(int i = 0; i < frames; i++){
            for(var edit : every) if(frame % edit.getKey() == 0) edit.getValue().run();
            for(Runnable edit : at.getOrDefault(frame, List.of())) edit.run();
            Vanilla.frame();
            shard.tick();
            frame++;
            compare("frame " + frame);
            for(var watch : watches) if(watch.getValue().getAsBoolean()) seen.merge(watch.getKey(), 1, Integer::sum);
            if(done.getAsBoolean()) break;
        }
        var batteries = new StringBuilder();
        for(var tile : world.tiles){
            var b = tile.build;
            if(b != null && b.tile == tile && b.block.name.equals("battery")){
                batteries.append(' ').append(b.tileX()).append(',').append(b.tileY()).append('=').append(b.power.status)
                    .append(" graph ").append(b.power.graph.all.size);
            }
        }
        System.out.println(frame + " frames, seen " + seen + ", next id " + (shard.ids().peek() - shardBase) + ", batteries" + batteries);
        chunks();
    }

    void run(int frames){
        run(frames, () -> false);
    }

    void compare(String at){
        sameRandom(at);
        assertEquals(lastId() - vanillaBase, shard.ids().peek() - shardBase, at + ": the entity id counter");
        same(at + ": Time.time", arc.util.Time.time, shard.timers().time());
        assertEquals(vanillaBuildings(), shardBuildings(), at + ": buildings");
        for(int i = 0; i < vanillaUnits.size(); i++){
            assertEquals(vanillaUnit(vanillaUnits.get(i)), shardUnit(shardUnits.get(i)), at + ": unit " + i);
        }
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

    // ---------------------------------------------------------------- state lines

    List<String> vanillaBuildings(){
        var out = new ArrayList<String>();
        for(int y = 0; y < world.height(); y++){
            for(int x = 0; x < world.width(); x++){
                var tile = world.tile(x, y);
                if(tile.build == null || tile.build.tile != tile) continue;
                out.add(vanillaBuilding(tile.build));
            }
        }
        return out;
    }

    List<String> shardBuildings(){
        var out = new ArrayList<String>();
        var tiles = shard.tiles();
        for(int y = 0; y < tiles.height(); y++){
            for(int x = 0; x < tiles.width(); x++){
                Building b = shard.buildings().at(x, y);
                if(b == null || b.tile() != tiles.pos(x, y)) continue;
                out.add(shardBuilding(b));
            }
        }
        return out;
    }

    private String vanillaBuilding(mindustry.gen.Building b){
        var out = new StringBuilder(b.block.name).append(" (").append(b.tile.x).append(", ").append(b.tile.y).append(") team ").append(b.team.id)
            .append(" health ").append(b.health).append(" timeScale ").append(b.timeScale()).append('/').append(read(b, "timeScaleDuration"))
            .append(" eff ").append(b.efficiency).append('/').append(b.optionalEfficiency).append('/').append(b.potentialEfficiency)
            .append(" enabled ").append(b.enabled).append(" added ").append(b.isAdded());
        if(b.items != null){
            out.append(" items");
            for(int i = 0; i < content.items().size; i++) if(b.items.get(i) > 0) out.append(' ').append(i).append('x').append(b.items.get(i));
        }
        if(b.liquids != null){
            out.append(" liquids");
            for(int i = 0; i < content.liquids().size; i++) if(b.liquids.get(content.liquid(i)) > 0) out.append(' ').append(i).append(':').append(b.liquids.get(content.liquid(i)));
        }
        if(b.power != null) out.append(" power ").append(b.power.status);
        if(b.timer != null) out.append(" timers ").append(Arrays.toString(b.timer.getTimes()));
        if(b instanceof mindustry.world.blocks.distribution.Conveyor.ConveyorBuild c){
            out.append(" slots");
            for(int i = 0; i < c.len; i++) out.append(' ').append(c.ids[i] == null ? -1 : c.ids[i].id).append('@').append(c.xs[i]).append(',').append(c.ys[i]);
            out.append(" mid ").append(c.mid).append(" minitem ").append(c.minitem);
        }
        String[][] fields = FIELDS.get(b.getClass().getSimpleName());
        if(fields == null && b.getClass().getSuperclass() != null) fields = FIELDS.get(b.getClass().getSuperclass().getSimpleName());
        if(fields != null){
            for(String[] f : fields){
                String value = vanillaValue(read(b, f[0]));
                if(b instanceof mindustry.world.blocks.units.RepairTower.RepairTowerBuild && f[0].equals("targets")) value = sorted(value);
                out.append(' ').append(f[0]).append(' ').append(value);
            }
        }
        return out.toString();
    }

    private String shardBuilding(Building b){
        var out = new StringBuilder(b.block().name()).append(" (").append(b.tileX()).append(", ").append(b.tileY()).append(") team ").append(b.team)
            .append(" health ").append(b.health).append(" timeScale ").append(b.timeScale).append('/').append(b.timeScaleDuration)
            .append(" eff ").append(b.efficiency).append('/').append(b.optionalEfficiency).append('/').append(b.potentialEfficiency)
            .append(" enabled ").append(b.enabled).append(" added ").append(b.isAdded());
        if(b.items != null){
            out.append(" items");
            for(int i = 0; i < EPOCH.items(); i++) if(b.items.get(i) > 0) out.append(' ').append(i).append('x').append(b.items.get(i));
        }
        if(b.liquids != null){
            out.append(" liquids");
            for(int i = 0; i < EPOCH.liquids(); i++) if(b.liquids.get(i) > 0) out.append(' ').append(i).append(':').append(b.liquids.get(i));
        }
        if(b.power != null) out.append(" power ").append(b.power.status);
        out.append(" timers ").append(Arrays.toString(b.timer));
        if(b instanceof ConveyorBuild c){
            out.append(" slots");
            for(int i = 0; i < c.len; i++) out.append(' ').append(c.ids[i]).append('@').append(c.xs[i]).append(',').append(c.ys[i]);
            out.append(" mid ").append(c.mid).append(" minitem ").append(c.minitem);
        }
        String vanillaClass = vanillaClassOf(b);
        String[][] fields = vanillaClass == null ? null : FIELDS.get(vanillaClass);
        if(fields != null){
            for(String[] f : fields){
                String value = shardValue(b, f[1], read(b, f[1]));
                if(b instanceof com.xpdustry.shard.sim.blocks.units.RepairTower && f[1].equals("targets")) value = sorted(value);
                out.append(' ').append(f[0]).append(' ').append(value);
            }
        }
        return out.toString();
    }

    /**
     * A list value with its entries sorted: for {@code Units.nearby}'s order, which is vanilla's team quadtree's and
     * Shard's unit grid's (W2.8's known deviation; the visits are independent heals).
     */
    private static String sorted(String list){
        if(list.length() <= 2) return list;
        var parts = new ArrayList<>(Arrays.asList(list.substring(1, list.length() - 1).split(", ")));
        Collections.sort(parts);
        return parts.toString();
    }

    /** The vanilla building class name of the W3.6 kind {@code b}, or null. */
    private static String vanillaClassOf(Building b){
        return switch(b.getClass().getSimpleName()){
            case "MendProjector" -> "MendBuild";
            case "OverdriveProjector" -> "OverdriveBuild";
            case "ForceProjector" -> "ForceBuild";
            case "RegenProjector" -> "RegenProjectorBuild";
            case "ShockwaveTower" -> "ShockwaveTowerBuild";
            case "Radar" -> "RadarBuild";
            case "BaseShield" -> "BaseShieldBuild";
            case "PointDefenseTurret" -> "PointDefenseBuild";
            case "TractorBeamTurret" -> "TractorBeamBuild";
            case "RepairTurret" -> "RepairPointBuild";
            case "RepairTower" -> "RepairTowerBuild";
            default -> null;
        };
    }

    private String vanillaValue(Object value){
        return switch(value){
            case null -> "-";
            case Float f -> Float.toString(f);
            case mindustry.gen.Bullet b -> "bullet " + (b.id - vanillaBase);
            case mindustry.gen.Unit u -> "unit " + (u.id - vanillaBase);
            case mindustry.gen.Building t -> "build " + t.tileX() + "," + t.tileY();
            case arc.struct.Seq<?> seq -> {
                var parts = new ArrayList<String>();
                for(Object o : seq) parts.add(vanillaValue(o));
                yield parts.toString();
            }
            default -> value.toString();
        };
    }

    private String shardValue(Building owner, String field, Object value){
        if(owner instanceof com.xpdustry.shard.sim.blocks.turret.PointDefenseTurret && field.equals("target")){
            Bullet bullet = Bullet.ofObject(shard, (Integer)value);
            return bullet == null ? "-" : "bullet " + (bullet.id() - shardBase);
        }
        return switch(value){
            case null -> "-";
            case Float f -> Float.toString(f);
            case Unit u -> "unit " + (u.id() - shardBase);
            case Building t -> "build " + t.tileX() + "," + t.tileY();
            case List<?> list -> {
                var parts = new ArrayList<String>();
                for(Object o : list) parts.add(shardValue(owner, "", o));
                yield parts.toString();
            }
            default -> value.toString();
        };
    }

    private static Object read(Object owner, String name){
        for(Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()){
            try{
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            }catch(NoSuchFieldException e){
                // a superclass may declare it
            }catch(ReflectiveOperationException e){
                throw new AssertionError(e);
            }
        }
        throw new AssertionError(owner.getClass().getName() + " has no field " + name);
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
            out.add(bullet(b.id - vanillaBase, b.type.id, b.team.id, b.x, b.y, b.vel.x, b.vel.y, b.time, b.lifetime, b.damage, b.absorbed));
        }
        return out;
    }

    List<String> shardBullets(){
        var out = new ArrayList<String>();
        Bullets t = shard.bullets();
        for(int r = 0; r < t.size(); r++){
            out.add(bullet(t.id[r] - shardBase, t.type[r], t.team[r], t.x[r], t.y[r], t.velX[r], t.velY[r], t.time[r], t.lifetime[r], t.damage[r],
                t.absorbed[r]));
        }
        return out;
    }

    private static String bullet(int id, int type, int team, float x, float y, float vx, float vy, float time, float lifetime, float damage,
                                 boolean absorbed){
        return id + " t" + type + " team" + team + " (" + x + ", " + y + ") v(" + vx + ", " + vy + ") time " + time + "/" + lifetime
            + " dmg " + damage + (absorbed ? " absorbed" : "");
    }

    /** Live bullets of {@code team} on vanilla's side. */
    static int vanillaBullets(Team team){
        int count = 0;
        for(mindustry.gen.Bullet b : mindustry.gen.Groups.bullet) if(b.team == team) count++;
        return count;
    }

    // ---------------------------------------------------------------- save chunks

    /** Every W3.6 building's save chunk, compared with vanilla's. */
    void chunks(){
        var current = ContentHeader.of(EPOCH).mapping(EPOCH);
        int compared = 0;
        for(int y = 0; y < world.height(); y++){
            for(int x = 0; x < world.width(); x++){
                var tile = world.tile(x, y);
                if(tile.build == null || tile.build.tile != tile) continue;
                Building b = shard.buildings().at(x, y);
                if(vanillaClassOf(b) == null) continue;
                assertEquals(hex(vanillaChunk(tile.build)), hex(CODECS.chunk(b, EPOCH, current)), tile.build.block.name + " at (" + x + ", " + y + "): save chunk");
                compared++;
            }
        }
        assertTrue(compared > 0, "no W3.6 building to compare the save chunk of");
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
