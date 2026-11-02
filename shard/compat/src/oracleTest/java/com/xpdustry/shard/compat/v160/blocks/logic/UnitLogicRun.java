package com.xpdustry.shard.compat.v160.blocks.logic;

import arc.math.*;
import arc.struct.*;
import arc.util.io.*;
import arc.util.pooling.*;
import com.xpdustry.shard.common.geom.Point2;
import com.xpdustry.shard.common.math.Rand;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.sync.CallFrames;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.blocks.logic.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.bullet.Bullets;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.weather.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import mindustry.ai.types.*;
import mindustry.entities.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.logic.*;
import mindustry.world.*;
import mindustry.world.blocks.logic.LogicBlock.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One processor-and-units fixture in a vanilla world and a Shard one, built and run in step (WP69's gates), compared
 * after every frame:
 * <ul>
 *   <li>{@code Time.time}, the random's state and the entity id counter (ids relative to each side's start);</li>
 *   <li>every unit in {@code Groups.unit} order: type, team, position, rotation, velocity, health, shield, elevation,
 *   stack, mine tile and timer, flag, plans, its controller's kind and AI timers, a logic AI's whole state, and its weapon
 *   mounts;</li>
 *   <li>every building's team, health and items, and every processor's variables (in vanilla's {@code vars} order),
 *   {@code @unit}, counter and text;</li>
 *   <li>the live bullets, and the weather;</li>
 *   <li>the Calls of the frame, byte for byte, as Shard's mappings send its events.</li>
 * </ul>
 * {@link #saveAt} has vanilla save its world mid-run; both load those bytes and run on, compared as before.
 *
 * <p>Pins, as the other unit gates have them: the pathfinder threads are stopped (no fixture walks a flowfield);
 * vanilla's {@code PhysicsWorld.rand} takes the state of Shard's layer randoms; the process-wide history
 * ({@code LExecutor.unitTimeouts}, the bullet pool, the lightning seed, the hazard pools) starts empty, as a fresh
 * server's. A position target ({@code PosTeam}) and a {@code bullet} result read as none on vanilla's side, as Shard
 * keeps neither as an object.
 */
final class UnitLogicRun implements AutoCloseable{
    static final Epoch EPOCH = Epoch.vanilla();
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final KindRegistry KINDS = KindRegistry.builtIn().withPassthrough();
    static final MsavReader READER = new MsavReader(Worlds.EPOCH);
    static final MapLoader LOADER = new MapLoader(Worlds.EPOCH, CODECS);
    /** The Calls processors and their units make, by packet id. */
    static final Set<Integer> CALLS = Set.of(Packets.SyncVariable.ID, Packets.SetFlag.ID, Packets.SetTile.ID, Packets.SetFloor.ID,
        Packets.SetOverlay.ID, Packets.SetMapArea.ID, Packets.TransferItemTo.ID, Packets.TakeItems.ID, Packets.LogicExplosion.ID,
        Packets.CreateWeather.ID, Packets.SpawnEffect.ID, Packets.UnitDeath.ID, Packets.UnitDestroy.ID, Packets.UnitDespawn.ID,
        Packets.UnitEnvDeath.ID, Packets.UnitCapDeath.ID, Packets.UnitSafeDeath.ID, Packets.CompleteObjective.ID, Packets.ClearObjectives.ID,
        Packets.SetObjectives.ID, Packets.CreateMarker.ID, Packets.RemoveMarker.ID, Packets.UpdateMarker.ID, Packets.UpdateMarkerText.ID,
        Packets.UpdateMarkerTexture.ID, Packets.PickedUnitPayload.ID, Packets.PickedBuildPayload.ID, Packets.PayloadDropped.ID,
        Packets.UnitBuildingControlSelect.ID);
    /** The Calls' families: {@code ucontrol itemDrop} and {@code itemTake} send theirs as {@link ItemEvent}s (W3.7). */
    static final List<Class<? extends WorldEvent>> EVENTS = List.of(LogicEvent.class, LogicWorldEvent.class, ItemEvent.class, WaveEvent.class,
        WeatherEvent.class, UnitEvent.class, com.xpdustry.shard.sim.objectives.ObjectiveEvent.class, PayloadEvent.class);
    private static final Field PHYSICS, WORLD_RAND, LAYERS, LAYER_RAND, TARGET, TIMER, MAIN_TARGET, POS_TARGET, CONTROL_TIMER,
        TARGET_TIMER;

    static{
        try{
            PHYSICS = open(mindustry.async.PhysicsProcess.class.getDeclaredField("physics"));
            WORLD_RAND = open(mindustry.async.PhysicsProcess.PhysicsWorld.class.getDeclaredField("rand"));
            LAYERS = open(com.xpdustry.shard.sim.spatial.UnitPhysics.class.getDeclaredField("layers"));
            LAYER_RAND = open(Class.forName("com.xpdustry.shard.sim.spatial.UnitPhysics$Layer").getDeclaredField("rand"));
            TARGET = open(AIController.class.getDeclaredField("target"));
            TIMER = open(AIController.class.getDeclaredField("timer"));
            MAIN_TARGET = open(LogicAI.class.getDeclaredField("mainTarget"));
            POS_TARGET = open(LogicAI.class.getDeclaredField("posTarget"));
            CONTROL_TIMER = open(LogicAI.class.getDeclaredField("controlTimer"));
            TARGET_TIMER = open(LogicAI.class.getDeclaredField("targetTimer"));
        }catch(ReflectiveOperationException e){
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Field open(Field field){
        field.setAccessible(true);
        return field;
    }

    World shard;
    int vanillaBase, shardBase;
    private final Pool<mindustry.gen.Bullet> pool;
    private boolean physicsPinned;
    private final Map<Integer, List<Runnable>> edits = new TreeMap<>();
    /** Edits whose Calls count in their frame: they run in the frame's capture, as the server, before it. */
    private final Map<Integer, List<Runnable>> sending = new TreeMap<>();
    /** More state a fixture compares, after the rest, at each {@link #same}. */
    java.util.function.Consumer<String> also = at -> {};
    private com.xpdustry.shard.sim.world.Observer observer;
    int frame, callCount, saveAt = -1, mostUnits;
    /** What a fixture checks it exercised, by name: counted by {@link #count}. */
    final TreeMap<String, Integer> seen = new TreeMap<>();

    /**
     * A {@code size × size} stone world in play on both sides, with no unit cap and no wave timer; {@code tiles} may
     * put ores and spawns on tiles before the load ({@code overlay} by position), as a map has them.
     */
    UnitLogicRun(int size, Map<Integer, String> overlays){
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
        mindustry.gen.Groups.updatePooling();
        mindustry.gen.Bullet.create();
        pool = Pools.get(mindustry.gen.Bullet.class, () -> null);
        pool.clear();
        clearUnitTimeouts();

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
        // GlobalVars.rand, as the oracle seeds it
        LogicPart.require(shard).logic().rand(new Rand(0));
        align();
    }

    /** Both sides' id counters and randoms from here on alike, ids counted from here; Shard's id goes to vanilla's. */
    private void align(){
        shardBase = shard.ids().peek();
        shard.rand().setState(Mathf.rand.seed0, Mathf.rand.seed1);
        setStatic(EntityGroup.class, "lastId", shardBase);
        setStatic(mindustry.entities.Lightning.class, "lastSeed", 0);
        vanillaBase = lastId();
    }

    @Override
    public void close(){
        pool.clear();
    }

    /** Stops vanilla's flowfield and control pathfinder threads: no fixture walks a field, so they only race. */
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

    /** {@code LExecutor.unitTimeouts} is process-wide; a fresh server's is empty. */
    private static void clearUnitTimeouts(){
        try{
            ((IntFloatMap)open(LExecutor.class.getDeclaredField("unitTimeouts")).get(null)).clear();
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- building the fixture

    /** {@code block} for {@code team} at (x, y), as a map editor places it. */
    void place(String block, int x, int y, Team team){
        world.tile(x, y).setBlock(content.block(block), team, 0);
        shard.buildings().setBlock(x, y, EPOCH.block(block), team.id, 0, Cause.SERVER);
        same("after placing " + block);
    }

    /**
     * A processor running {@code code}, linked to each {@code (x, y)} in {@code links}, as a player configures it; a
     * world processor as one may with {@code allowEditWorldProcessors}, which is then off again.
     */
    void processor(String block, int x, int y, Team team, String code, int... links){
        place(block, x, y, team);
        state.rules.allowEditWorldProcessors = true;
        shard.part(WorldRules.class).rules.allowEditWorldProcessors = true;
        world.build(x, y).configureAny(mindustry.world.blocks.logic.LogicBlock.compress(code, new Seq<>()));
        Vanilla.runPosted();
        shard.buildings().at(x, y).configureAny(LogicConfig.compress(code, List.of()));
        for(int i = 0; i < links.length; i += 2){
            world.build(x, y).configureAny(arc.math.geom.Point2.pack(links[i], links[i + 1]));
            Vanilla.runPosted();
            shard.buildings().at(x, y).configureAny(Point2.pack(links[i], links[i + 1]));
        }
        state.rules.allowEditWorldProcessors = false;
        shard.part(WorldRules.class).rules.allowEditWorldProcessors = false;
        assertFalse(world.build(x, y) instanceof LogicBuild logic && logic.code.isEmpty(), "the processor took its code");
    }

    /** New code for the processor at (x, y), as a player uploads it. */
    void code(int x, int y, String code){
        world.build(x, y).configureAny(mindustry.world.blocks.logic.LogicBlock.compress(code, new Seq<>()));
        Vanilla.runPosted();
        shard.buildings().at(x, y).configureAny(LogicConfig.compress(code, List.of()));
    }

    /** Items put into a building, as a map editor's. */
    void give(int x, int y, String item, int amount){
        world.build(x, y).items.add(content.item(item), amount);
        shard.buildings().at(x, y).items.add(EPOCH.item(item).id(), amount);
    }

    /** A unit of {@code type} with its type's controller, at world (x, y). */
    void spawn(String type, Team team, float x, float y){
        content.unit(type).spawn(team, x, y);
        Unit.spawn(shard, EPOCH.unit(type), team.id, x, y, 0f);
        same("after spawning " + type);
    }

    /** A unit of {@code type} that stands still and does nothing ({@code NoAI}), at world (x, y). */
    void dummy(String type, Team team, float x, float y){
        var v = content.unit(type).create(team);
        v.controller(new NoAI());
        v.set(x, y);
        v.add();
        Unit s = Unit.create(shard, EPOCH.unit(type), team.id);
        s.controller(new NoAi());
        s.set(x, y);
        s.add();
        same("after placing a dummy " + type);
    }

    /** A connected player named {@code name} on the {@code index}-th unit's team, who takes that unit over. */
    void player(int index, String name){
        var vanillaUnit = mindustry.gen.Groups.unit.index(index);
        var v = mindustry.gen.Player.create();
        v.con = Vanilla.connection();
        v.name = name;
        v.team(vanillaUnit.team);
        v.add();
        v.unit(vanillaUnit);
        Vanilla.runPosted();
        Unit shardUnit = shard.units().get(index);
        var s = com.xpdustry.shard.sim.unit.Player.create(shard);
        s.name = name;
        s.color = v.color.rgba();
        s.team(shardUnit.team);
        s.add();
        s.unit(shardUnit);
        same("after " + name + " took a unit");
    }

    /** Damage to the building at (x, y), as a bullet deals it. */
    void damage(int x, int y, float amount){
        world.build(x, y).damage(amount);
        shard.buildings().at(x, y).damage(amount);
    }

    /** A wave group of {@code unit}s, at the spawn {@code (spawnX, spawnY)} or every one (-1), with a status or none. */
    void waveGroup(String unit, int amount, int spawnX, int spawnY, String effect){
        var v = new mindustry.game.SpawnGroup(content.unit(unit));
        v.unitAmount = amount;
        v.spawn = spawnX < 0 ? -1 : arc.math.geom.Point2.pack(spawnX, spawnY);
        if(effect != null) v.effect = content.statusEffect(effect);
        state.rules.spawns.add(v);
        var s = new com.xpdustry.shard.sim.rules.SpawnGroup(EPOCH.unit(unit).id());
        s.unitAmount = amount;
        s.spawn = spawnX < 0 ? -1 : Point2.pack(spawnX, spawnY);
        if(effect != null) s.effect = EPOCH.status(effect).id();
        shard.part(WorldRules.class).rules.spawns.add(s);
    }

    /** Shard's value of the variable {@code name} in the processor at (x, y), as the harness prints it. */
    String variable(int x, int y, String name){
        Processor p = ((LogicBlock)shard.buildings().at(x, y)).processor();
        for(int i = 0; i < p.program().variables(); i++){
            if(p.program().variableName(i).equals(name)) return shardValue(p.variable(i));
        }
        throw new AssertionError("no variable " + name);
    }

    /** The vanilla and Shard unit of the {@code index}-th unit in group order. */
    void unit(int index, BiConsumer<mindustry.gen.Unit, Unit> edit){
        edit.accept(mindustry.gen.Groups.unit.index(index), shard.units().get(index));
    }

    void at(int when, Runnable edit){
        edits.computeIfAbsent(when, k -> new ArrayList<>()).add(edit);
    }

    /** {@link #at}, with the Calls the edit makes counted in the frame's ({@link #sending}). */
    void sending(int when, Runnable edit){
        sending.computeIfAbsent(when, k -> new ArrayList<>()).add(edit);
    }

    /** Vanilla saves the world before frame {@code frame}; both load it and run on. */
    void saveAt(int frame){
        saveAt = frame;
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
            for(Runnable edit : edits.getOrDefault(frame, List.of())) edit.run();
            pinPhysics();
            int before = Vanilla.captured().size();
            for(Runnable edit : sending.getOrDefault(frame, List.of())) Vanilla.net(Vanilla.NetMode.server, edit);
            Vanilla.net(Vanilla.NetMode.server, Vanilla::frame);
            shard.tick();
            var sent = new ArrayList<>(Vanilla.captured().subList(before, Vanilla.captured().size()));
            var events = observer.drain();
            for(var event : events) count(event.getClass().getSimpleName());
            mostUnits = Math.max(mostUnits, shard.units().size());
            sameCalls(CallFrames.vanilla(sent, CALLS), CallFrames.shard(shard, events), "Calls in frame " + frame);
            same("after frame " + frame);
        }
        assertTrue(physicsPinned || shard.units().size() == 0, "physics pinned");
    }

    /** Unit deaths in one blast come in grid order on Shard's side (W2.11's deviation): those compare as a set. */
    private void sameCalls(List<String> expected, List<String> actual, String when){
        callCount += expected.size();
        if(expected.equals(actual)) return;
        var sortedExpected = new ArrayList<>(expected);
        var sortedActual = new ArrayList<>(actual);
        sortedExpected.sort(null);
        sortedActual.sort(null);
        boolean deaths = expected.stream().anyMatch(line -> line.contains(" " + Packets.UnitDeath.ID + " "));
        if(deaths && sortedExpected.equals(sortedActual)) return;
        assertEquals(expected, actual, when);
    }

    /** Vanilla's {@code SaveIO.write}, loaded by both, as the server's {@code load} command does. */
    private void saveAndLoad(){
        same("before saving");
        var file = new ByteArrayOutputStream();
        SaveIO.write(file, new SaveOptions());
        // both load from a generator seeded with 0, as Shard's loaded world is (Worlds.config(0))
        Mathf.rand.setSeed(0);
        // a fresh server's id counter: units carried by units and blocks draw their ids while the save is read
        setStatic(EntityGroup.class, "lastId", 0);
        SaveIO.load(new ByteArrayInputStream(file.toByteArray()), world.context);
        state.set(mindustry.core.GameState.State.playing);
        Vanilla.runPosted();
        // the load frees the old world's entities into the process-wide pools: a fresh server's are empty (a pooled
        // bullet keeps the id it drew before the save, and Groups' free queue draws ids when it frees)
        HazardPools.empty();
        try{
            ((Seq<?>)open(Groups.class.getDeclaredField("freeQueue")).get(null)).clear();
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
        pool.clear();
        setStatic(mindustry.entities.Lightning.class, "lastSeed", 0);
        stopPathfinders();
        clearUnitTimeouts();
        SaveFile save = READER.read(file.toByteArray());
        observer.close();
        shard = LOADER.load(save, Worlds.config(0), LOADER.saveRules(save)).world();
        LogicPart.require(shard).logic().rand(new Rand(GlobalVars.rand.seed0, GlobalVars.rand.seed1));
        observer = shard.observe(4096, EVENTS, null);
        // ids are the save's on both sides now: vanilla's counter goes to the one Shard's loaded world has
        setStatic(EntityGroup.class, "lastId", shard.ids().peek());
        vanillaBase = shardBase = 0;
        physicsPinned = false;
        same("loaded");
    }

    /** Once vanilla has its physics worlds: their stacked-body randoms take the state of Shard's layers'. */
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

    // ---------------------------------------------------------------- comparing

    /** Fails with {@code at} unless both sides are alike, naming every part that differs. */
    void same(String at){
        int vanillaNext = lastId() - vanillaBase, shardNext = shard.ids().peek() - shardBase;
        List<String> vanillaUnits = vanillaUnits(), shardUnits = shardUnits();
        List<String> vanillaBuildings = vanillaBuildings(), shardBuildings = shardBuildings();
        List<String> vanillaBullets = vanillaBullets(), shardBullets = shardBullets();
        List<String> vanillaWeather = vanillaWeather(), shardWeather = shardWeather();
        assertAll(at,
            () -> assertEquals(arc.util.Time.time, shard.timers().time(), "Time.time"),
            () -> assertEquals(Mathf.rand.seed0, shard.rand().getState(0), "random state"),
            () -> assertEquals(Mathf.rand.seed1, shard.rand().getState(1), "random state"),
            () -> assertEquals(vanillaNext, shardNext, "the entity id counter"),
            () -> sameLines(vanillaUnits, shardUnits, "units"),
            () -> sameLines(vanillaBuildings, shardBuildings, "buildings"),
            () -> sameLines(vanillaBullets, shardBullets, "live bullets"),
            () -> assertEquals(vanillaWeather, shardWeather, "weather"));
        also.accept(at);
    }

    /** The first line that differs, whole, rather than both lists. */
    static void sameLines(List<String> expected, List<String> actual, String what){
        for(int i = 0; i < Math.min(expected.size(), actual.size()); i++){
            if(!expected.get(i).equals(actual.get(i))) assertEquals(expected.get(i), actual.get(i), what + " #" + i);
        }
        assertEquals(expected.size(), actual.size(), what + ": " + expected + " vs " + actual);
    }

    // units

    List<String> vanillaUnits(){
        var out = new ArrayList<String>();
        for(mindustry.gen.Unit u : mindustry.gen.Groups.unit){
            var line = new StringBuilder(unit(u.id - vanillaBase, u.type.name, u.team.id, u.x, u.y, u.rotation, u.vel.x, u.vel.y, u.health,
                u.shield, u.elevation, u.dead));
            line.append(" stack ").append(u.stack.amount == 0 ? "-" : u.stack.item.name + "=" + u.stack.amount)
                .append(" mine ").append(u.mineTile == null ? "-" : u.mineTile.x + "," + u.mineTile.y).append(' ').append(bits(u.mineTimer)).append(" flag ").append(u.flag)
                .append(" plans ").append(u.plans.size).append(u.plans.isEmpty() ? "" : " first " + plan(u.plans.first()))
                .append(" shooting ").append(u.isShooting).append(" aim ").append(bits(u.aimX)).append(',').append(bits(u.aimY));
            var controller = u.controller();
            line.append(" ctrl ").append(controllerName(controller));
            if(controller instanceof AIController ai){
                line.append(" timers ").append(Arrays.toString(timers(ai))).append(" target ").append(name(get(TARGET, ai)));
            }
            if(controller instanceof LogicAI ai){
                var pos = (PosTeam)get(POS_TARGET, ai);
                line.append(" logic ").append(ai.control).append(' ').append(bits(ai.moveX)).append(',').append(bits(ai.moveY)).append(" r ")
                    .append(bits(ai.moveRad)).append(" timers ").append(bits((float)get(CONTROL_TIMER, ai))).append('/')
                    .append(bits((float)get(TARGET_TIMER, ai))).append(" processor ").append(name(ai.controller)).append(" aim ")
                    .append(ai.aimControl).append(' ').append(bits(pos.x)).append(',').append(bits(pos.y)).append(" main ")
                    .append(name(get(MAIN_TARGET, ai))).append(" shoot ").append(ai.shoot).append(" boost ").append(ai.boost)
                    .append(" plan ").append(plan(ai.plan));
            }
            for(mindustry.entities.units.WeaponMount m : u.mounts){
                line.append(" [").append(bits(m.reload)).append(' ').append(bits(m.rotation)).append(' ').append(m.totalShots).append(' ')
                    .append(bits(m.aimX)).append(',').append(bits(m.aimY)).append(' ').append(m.shoot).append(' ').append(m.rotate).append(' ')
                    .append(m.target instanceof PosTeam ? "-" : name(m.target)).append(']');
            }
            out.add(line.toString());
        }
        return out;
    }

    List<String> shardUnits(){
        var out = new ArrayList<String>();
        for(int i = 0; i < shard.units().size(); i++){
            Unit u = shard.units().get(i);
            var line = new StringBuilder(unit(u.id() - shardBase, u.type().name(), u.team, u.x, u.y, u.rotation, u.vel.x, u.vel.y, u.health,
                u.shield, u.elevation, u.dead));
            line.append(" stack ").append(u.stackAmount == 0 ? "-" : EPOCH.item(u.stackItem).name() + "=" + u.stackAmount)
                .append(" mine ").append(u.mineTile == -1 ? "-" : Point2.x(u.mineTile) + "," + Point2.y(u.mineTile)).append(' ').append(bits(u.mineTimer)).append(" flag ")
                .append(u.flag).append(" plans ").append(u.plans.size()).append(u.plans.isEmpty() ? "" : " first " + plan(u.plans.first()))
                .append(" shooting ").append(u.isShooting).append(" aim ").append(bits(u.aimX)).append(',').append(bits(u.aimY));
            line.append(" ctrl ").append(u.controller instanceof AiController ai ? ai.kind : u.controller instanceof NoAi ? "no-ai"
                : u.controller instanceof PlayerControl ? "player" : u.controller.getClass().getSimpleName());
            if(u.controller instanceof AiController ai){
                line.append(" timers ").append(Arrays.toString(ai.timers)).append(" target ").append(name(ai.target));
            }
            if(u.controller instanceof LogicAi ai){
                line.append(" logic ").append(ai.control).append(' ').append(bits(ai.moveX)).append(',').append(bits(ai.moveY)).append(" r ")
                    .append(bits(ai.moveRad)).append(" timers ").append(bits(ai.controlTimer)).append('/').append(bits(ai.targetTimer))
                    .append(" processor ").append(name(ai.controller)).append(" aim ").append(ai.aimControl).append(' ').append(bits(ai.posX))
                    .append(',').append(bits(ai.posY)).append(" main ").append(name(ai.mainTarget)).append(" shoot ").append(ai.shoot)
                    .append(" boost ").append(ai.boost).append(" plan ").append(plan(ai.plan));
            }
            for(com.xpdustry.shard.sim.unit.WeaponMount m : u.mounts){
                line.append(" [").append(bits(m.reload)).append(' ').append(bits(m.rotation)).append(' ').append(m.totalShots).append(' ')
                    .append(bits(m.aimX)).append(',').append(bits(m.aimY)).append(' ').append(m.shoot).append(' ').append(m.rotate).append(' ')
                    .append(name(m.target)).append(']');
            }
            out.add(line.toString());
        }
        return out;
    }

    private static String unit(int id, String type, int team, float x, float y, float rotation, float vx, float vy, float health, float shield,
                               float elevation, boolean dead){
        return "#" + id + " " + type + " t" + team + " at " + bits(x) + "," + bits(y) + " rot " + bits(rotation) + " v " + bits(vx) + ","
            + bits(vy) + " hp " + bits(health) + " shield " + bits(shield) + " el " + bits(elevation) + (dead ? " dead" : "");
    }

    /** {@code GroundAI} as {@code ground-ai}, Shard's kind names. */
    private static String controllerName(Object controller){
        String name = controller.getClass().getSimpleName();
        if(controller instanceof mindustry.gen.Player) return "player";
        if(name.endsWith("AI")) name = name.substring(0, name.length() - 2);
        return name.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT) + "-ai";
    }

    private static float[] timers(AIController ai){
        return ((arc.util.Interval)get(TIMER, ai)).getTimes();
    }

    private static String plan(mindustry.entities.units.BuildPlan plan){
        return plan.x + "," + plan.y + " " + (plan.breaking ? "break" : plan.block == null ? "-" : plan.block.name) + " r" + plan.rotation
            + " p" + bits(plan.progress) + (plan.initialized ? " init" : "") + (plan.stuck ? " stuck" : "");
    }

    private static String plan(com.xpdustry.shard.sim.unit.BuildPlan plan){
        return plan.x + "," + plan.y + " " + (plan.breaking ? "break" : plan.block < 0 ? "-" : EPOCH.block(plan.block).name()) + " r" + plan.rotation
            + " p" + bits(plan.progress) + (plan.initialized ? " init" : "") + (plan.stuck ? " stuck" : "");
    }

    // buildings and processors

    List<String> vanillaBuildings(){
        var out = new ArrayList<String>();
        for(var tile : world.tiles){
            var b = tile.build;
            if(b == null || b.tile != tile) continue;
            var line = new StringBuilder(b.block.name + "@" + b.tile.x + "," + b.tile.y + " t" + b.team.id + " hp " + bits(b.health) + " items "
                + LogicOracleTest.items(id -> b.items == null ? 0 : b.items.get(id)));
            // a processor's executor and variables come with its first code
            if(b instanceof LogicBuild logic && !logic.code.isEmpty()){
                line.append(" counter ").append(LogicOracleTest.num(logic.executor.counter.numval)).append(" @unit ")
                    .append(vanillaValue(logic.executor.unit.obj()));
                for(LVar v : logic.executor.vars) line.append(' ').append(v.name).append('=').append(v.isobj ? vanillaValue(v.objval) : LogicOracleTest.num(v.numval));
                line.append(" text \"").append(logic.executor.textBuffer).append('"');
            }
            out.add(line.toString());
        }
        return out;
    }

    List<String> shardBuildings(){
        var out = new ArrayList<String>();
        var tiles = shard.tiles();
        for(int pos = 0; pos < tiles.size(); pos++){
            Building b = shard.buildings().at(pos);
            if(b == null || b.tile() != pos) continue;
            var line = new StringBuilder(b.block().name() + "@" + b.tileX() + "," + b.tileY() + " t" + b.team + " hp " + bits(b.health) + " items "
                + LogicOracleTest.items(id -> b.items == null ? 0 : b.items.get(id)));
            if(b instanceof LogicBlock logic && !logic.processor().code().isEmpty()){
                Processor p = logic.processor();
                line.append(" counter ").append(shardValue(p.counterValue())).append(" @unit ").append(shardValue(p.unit()));
                for(int i = 0; i < p.program().variables(); i++){
                    line.append(' ').append(p.program().variableName(i)).append('=').append(shardValue(p.variable(i)));
                }
                line.append(" text \"").append(p.text()).append('"');
            }
            out.add(line.toString());
        }
        return out;
    }

    String vanillaValue(Object value){
        return switch(value){
            case null -> LogicOracleTest.vanillaValue(null);
            case mindustry.gen.Unit u -> "unit#" + (u.id - vanillaBase);
            // Shard's bullets are table rows, not objects: a bullet statement's result is null
            case mindustry.gen.Bullet b -> "null";
            case Seq<?> seq -> {
                var joined = new StringJoiner(", ", "[", "]");
                for(Object o : seq) joined.add(vanillaValue(o));
                yield joined.toString();
            }
            default -> LogicOracleTest.vanillaValue(value);
        };
    }

    String shardValue(Object value){
        return switch(value){
            case null -> LogicOracleTest.shardValue(null);
            case Unit u -> "unit#" + (u.id() - shardBase);
            case List<?> list -> {
                var joined = new StringJoiner(", ", "[", "]");
                for(Object o : list) joined.add(shardValue(o));
                yield joined.toString();
            }
            default -> LogicOracleTest.shardValue(value);
        };
    }

    /** A target's name: a unit by relative id, a building by tile. */
    String name(Object target){
        return switch(target){
            case null -> "-";
            // Shard keeps a position target as coordinates, not an entity
            case PosTeam p -> "-";
            case mindustry.gen.Unit u -> "unit#" + (u.id - vanillaBase);
            case mindustry.gen.Building b -> "build@" + b.tile.x + "," + b.tile.y;
            case Unit u -> "unit#" + (u.id() - shardBase);
            case Building b -> "build@" + b.tileX() + "," + b.tileY();
            default -> target.getClass().getSimpleName();
        };
    }

    // bullets and weather

    List<String> vanillaBullets(){
        var out = new ArrayList<String>();
        for(mindustry.gen.Bullet b : mindustry.gen.Groups.bullet){
            out.add((b.id - vanillaBase) + " t" + b.type.id + " team " + b.team.id + " (" + bits(b.x) + ", " + bits(b.y) + ") v(" + bits(b.vel.x)
                + ", " + bits(b.vel.y) + ") time " + bits(b.time) + " dmg " + bits(b.damage));
        }
        return out;
    }

    List<String> shardBullets(){
        var out = new ArrayList<String>();
        Bullets t = shard.bullets();
        for(int r = 0; r < t.size(); r++){
            out.add((t.id[r] - shardBase) + " t" + t.type[r] + " team " + t.team[r] + " (" + bits(t.x[r]) + ", " + bits(t.y[r]) + ") v(" + bits(t.velX[r])
                + ", " + bits(t.velY[r]) + ") time " + bits(t.time[r]) + " dmg " + bits(t.damage[r]));
        }
        return out;
    }

    List<String> vanillaWeather(){
        var out = new ArrayList<String>();
        for(mindustry.gen.WeatherState w : mindustry.gen.Groups.weather){
            out.add((w.id - vanillaBase) + " " + w.weather.id + " intensity " + bits(w.intensity) + " opacity " + bits(w.opacity) + " life " + bits(w.life)
                + " wind " + bits(w.windVector.x) + "," + bits(w.windVector.y));
        }
        return out;
    }

    List<String> shardWeather(){
        var out = new ArrayList<String>();
        var weathers = Weathers.of(shard);
        if(weathers == null) return out;
        for(int i = 0; i < weathers.list().size(); i++){
            var w = weathers.list().get(i);
            out.add((w.id() - shardBase) + " " + w.weather + " intensity " + bits(w.intensity) + " opacity " + bits(w.opacity) + " life " + bits(w.life)
                + " wind " + bits(w.windVector.x) + "," + bits(w.windVector.y));
        }
        return out;
    }

    // plumbing

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
            open(owner.getDeclaredField(name)).setInt(null, value);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }
}
