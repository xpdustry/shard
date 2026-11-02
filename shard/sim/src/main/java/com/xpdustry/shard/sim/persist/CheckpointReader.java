package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.io.*;
import java.util.*;
import java.util.function.*;

/**
 * Restores a world from {@link CheckpointWriter}'s sections, then rebuilds what is derived:
 * <ol>
 *   <li>the stored state: tables, objects (created from their types first, so references resolve both ways), lists,
 *   timers, members, the log position and the journal;</li>
 *   <li>the tables' derived state: the grid's bitsets and the building chunk index
 *   ({@code Buildings.rebuildDerived()});</li>
 *   <li>the authoritative parts, {@link SectionPart}s from their own sections;</li>
 *   <li>derived parts: a fresh instance each, {@code init(world)} then {@code rebuild(world)}, in part order;</li>
 *   <li>every restored object's own {@code restored()} hook, in read order;</li>
 *   <li>the registered {@link RebuildHook}s.</li>
 * </ol>
 */
final class CheckpointReader{
    static World restore(Container.Decoded file, WorldConfig config){
        Header header = file.header();
        if(!header.kind().equals("checkpoint")) throw new CheckpointException("this file is a " + header.kind() + ", not a checkpoint");
        ContentEpoch content = config.content();
        if(!header.content().equals(content.hash())){
            throw new CheckpointException("the checkpoint was taken with content " + header.content() + "; this world has " + content.hash());
        }
        World.Restoring restoring = World.restoring(config);
        World world = restoring.world();
        var reading = new Reading(world, content, CheckpointWriter.readSchemas(file.section("schema")));

        section(file, "world", reading, in -> {
            int width = in.i("width"), height = in.i("height");
            if(width != config.width() || height != config.height()){
                throw new CheckpointException("the checkpoint is " + width + "×" + height + "; the config says " + config.width() + "×" + config.height());
            }
            in.l("seed");
            in.i("inboxCapacity");
            restoring.ticks(in.l("ticks"));
            world.rand().setState(in.l("seed0"), in.l("seed1"));
            restoring.paused(in.bool("paused"));
            world.ids().read(in);
        });
        section(file, "tiles", reading, in -> world.tiles().read(in));

        Object[] objects = createObjects(file, reading, world);
        reading.objects(objects);
        fillObjects(file, reading, objects);

        section(file, "buildings", reading, in -> world.buildings().read(in));
        var builds = new ActiveList<>(Building.updateSlot());
        section(file, "builds", reading, in -> list(in, builds, Building.class, reading));
        var units = new ActiveList<>(Unit.updateSlot());
        section(file, "units", reading, in -> list(in, units, Unit.class, reading));
        var bullets = new Bullets(world.ids());
        section(file, "bullets", reading, bullets::read);
        restoring.objects(builds, units, bullets);

        section(file, "timers", reading, in -> {
            double time = in.d("time");
            float[] delays = in.floats("runDelays");
            List<Timers.Task> tasks = in.list("runs", Timers.Task.class);
            if(delays.length != tasks.size()) throw new CheckpointException("the timers' delays and tasks differ in count");
            var pending = new ArrayList<Timers.Pending>(delays.length);
            for(int i = 0; i < delays.length; i++) pending.add(new Timers.Pending(delays[i], tasks.get(i)));
            restoring.timers(time, pending);
        });
        section(file, "members", reading, in -> {
            long[] sessions = in.longs("members");
            int[] routes = in.ints("routeGenerations");
            long[] sequences = in.longs("lastSequences");
            if(routes.length != sessions.length || sequences.length != sessions.length) throw new CheckpointException("the members' columns differ in length");
            for(int i = 0; i < sessions.length; i++) restoring.member(sessions[i], new Members.Member(routes[i], sequences[i]));
        });
        section(file, "log", reading, in -> {
            in.implicit("entries");
            restoring.logPosition(in.l("position"));
        });
        section(file, "journal", reading, in -> restoring.journal(journal(in, config)));
        world.buildings().rebuildDerived();

        List<WorldPart> derived = new ArrayList<>();
        restoring.parts(parts(file, reading, config, derived));
        World restored = restoring.finish();
        for(WorldPart part : derived){
            part.init(restored);
            part.rebuild(restored);
        }
        reading.runHooks();
        var hooks = new ArrayList<>(Registries.instances(RebuildHook.class));
        hooks.sort(Comparator.comparingInt(RebuildHook::order).thenComparing(hook -> hook.getClass().getName()));
        for(RebuildHook hook : hooks) hook.rebuild(restored);
        if(file.sections().containsKey("inbox")) inbox(file, reading, restored);
        return restored;
    }

    /**
     * The inputs that were waiting, back in the inbox under their arrival numbers. Last, once every object and part is
     * back: the inputs find their world objects by identity.
     */
    private static void inbox(Container.Decoded file, Reading reading, World world){
        reading.identities = true;
        try{
            section(file, "inbox", reading, in -> {
                long next = in.l("nextArrival");
                long[] numbers = in.longs("arrivals");
                List<Input> inputs = in.list("inputs", Input.class);
                if(numbers.length != inputs.size()) throw new CheckpointException("the inbox's arrival numbers and inputs differ in count");
                var pending = new ArrayList<Inbox.Arrival>(numbers.length);
                for(int i = 0; i < numbers.length; i++){
                    if(numbers[i] >= next || i > 0 && numbers[i] <= numbers[i - 1]) throw new CheckpointException("the inbox's arrival numbers are out of order");
                    pending.add(new Inbox.Arrival(numbers[i], inputs.get(i)));
                }
                world.inbox().restore(new Inbox.Snapshot(next, pending));
            });
        }finally{
            reading.identities = false;
        }
    }

    private static void section(Container.Decoded file, String name, Reading reading, Consumer<CheckpointIn> body){
        var source = new Source(name, file.section(name));
        var in = new CheckpointIn(source, reading);
        in.begin(reading.section(name));
        body.accept(in);
        in.end();
        source.finish();
    }

    /** Every object, blank, from the {@code types} section: entities bound to the world and their content. */
    private static Object[] createObjects(Container.Decoded file, Reading reading, World world){
        var holder = new Object[1][];
        section(file, "types", reading, in -> {
            int count = in.i("count");
            int[] classes = in.ints("classes");
            int[] defs = in.ints("defs");
            if(count < 0 || classes.length != count || defs.length != count) throw new CheckpointException("the object types are inconsistent");
            var objects = new Object[count];
            for(int i = 0; i < count; i++){
                if(classes[i] == -1) continue;
                Class<?> type = reading.type(classes[i]);
                objects[i] = blank(type, defs[i], reading, world);
            }
            holder[0] = objects;
        });
        return holder[0];
    }

    private static Object blank(Class<?> type, int def, Reading reading, World world){
        if(Building.class.isAssignableFrom(type)){
            BlockDef block = reading.block(def);
            if(block == null) throw new CheckpointException("a " + type.getName() + " has no block");
            Class<? extends Building> kind = world.kinds().type(block);
            if(kind != type){
                throw new CheckpointException("block " + block.name() + " is now simulated by " + kind.getName() + "; the checkpoint has a " + type.getName());
            }
            var building = (Building)Codecs.plan(type).create();
            Access.ENTITY_WORLD.set(building, world);
            Access.BUILDING_BLOCK.set(building, block);
            return building;
        }
        if(Unit.class.isAssignableFrom(type)){
            UnitDef unitType = reading.unitType(def);
            if(unitType == null) throw new CheckpointException("a unit has no type");
            var unit = (Unit)Codecs.plan(type).create();
            Access.ENTITY_WORLD.set(unit, world);
            Access.UNIT_TYPE.set(unit, unitType);
            return unit;
        }
        if(Entity.class.isAssignableFrom(type)){
            var entity = (Entity)Codecs.plan(type).create();
            Access.ENTITY_WORLD.set(entity, world);
            return entity;
        }
        if(Copyable.class.isAssignableFrom(type)) return Codecs.plan(type).create();
        throw new CheckpointException(type.getName() + " is stored as a world object but is not a Copyable");
    }

    private static void fillObjects(Container.Decoded file, Reading reading, Object[] objects){
        var source = new Source("objects", file.section("objects"));
        var in = new CheckpointIn(source, reading);
        for(Object value : objects){
            if(value == null) continue;
            Plan plan = Codecs.plan(value.getClass());
            in.begin(reading.schema(value.getClass().getName()));
            plan.fill(value, in);
            in.end();
            reading.created(value, plan);
        }
        source.finish();
    }

    private static <T extends Entity> void list(CheckpointIn in, ActiveList<T> list, Class<T> type, Reading reading){
        in.implicit("slot");
        var items = new Entity[in.i("capacity")];
        int[] indexes = in.ints("items");
        int size = in.i("size");
        if(size != indexes.length || size > items.length) throw new CheckpointException("an update list's size is inconsistent");
        for(int i = 0; i < size; i++){
            T entity = reading.object(indexes[i], type);
            int slot = entity instanceof Building building ? Building.updateSlot().get(building) : Unit.updateSlot().get((Unit)entity);
            if(slot != i) throw new CheckpointException(entity + " is at " + i + " in its update list but remembers " + slot);
            items[i] = entity;
        }
        Access.LIST_ITEMS.set(list, items);
        Access.LIST_SIZE.set(list, size);
        Access.LIST_INDEX.set(list, in.i("index"));
        in.derived("clearing");
    }

    private static Journal journal(CheckpointIn in, WorldConfig config){
        in.b("kind");
        in.i("retention");
        long start = in.l("start");
        long[] ticks = in.longs("ticks");
        int[] pos = in.ints("pos");
        short[] floor = in.shorts("floor"), overlay = in.shorts("overlay"), block = in.shorts("block");
        byte[] team = in.bytes("team");
        int[] build = in.ints("build");
        byte[] data = in.bytes("data"), floorData = in.bytes("floorData"), overlayData = in.bytes("overlayData");
        int[] extraData = in.ints("extraData");
        byte[] kinds = in.bytes("causeKinds");
        long[] numbers = in.longs("causeNumbers");
        int[] texts = in.ints("causeTexts");
        int[] lengths = in.ints("textLengths");
        String joined = in.str("texts");
        int n = ticks.length;
        if(pos.length != n || floor.length != n || overlay.length != n || block.length != n || team.length != n || build.length != n
            || data.length != n || floorData.length != n || overlayData.length != n || extraData.length != n
            || kinds.length != n || numbers.length != n || texts.length != n){
            throw new CheckpointException("the journal's columns differ in length");
        }
        var table = new String[lengths.length];
        int at = 0;
        for(int i = 0; i < lengths.length; i++){
            if(lengths[i] < 0 || at + lengths[i] > joined.length()) throw new CheckpointException("the journal's text table is corrupt");
            table[i] = joined.substring(at, at + lengths[i]);
            at += lengths[i];
        }
        Journal journal = config.journal().emptyAt(start);
        for(int i = 0; i < n; i++){
            String text = texts[i] == -1 ? null : table[Objects.checkIndex(texts[i], table.length)];
            Cause cause = switch(kinds[i]){
                case 0 -> Cause.SERVER;
                case 1 -> new Cause.Player(numbers[i]);
                case 2 -> new Cause.Team((int)numbers[i]);
                case 3 -> new Cause.Admin(text);
                case 4 -> new Cause.Ai((int)numbers[i]);
                case 5 -> new Cause.Plugin(text);
                default -> throw new CheckpointException("unknown cause kind " + kinds[i]);
            };
            journal.record(new Journal.Entry(ticks[i], cause, new TileImage(pos[i], floor[i], overlay[i], block[i], team[i], build[i],
                data[i], floorData[i], overlayData[i], extraData[i])));
        }
        return journal;
    }

    /** A new part of a class the checkpoint names: through the config's systems, else by its public constructor. */
    private static WorldPart part(WorldConfig config, Class<?> type){
        return config.systems().has(type) ? config.systems().create(type.asSubclass(WorldPart.class)) : (WorldPart)Codecs.construct(type);
    }

    /** The config's parts, in order: authoritative ones read from the checkpoint, derived ones fresh (into {@code derived}). */
    private static List<WorldPart> parts(Container.Decoded file, Reading reading, WorldConfig config, List<WorldPart> derived){
        var source = new Source("parts", file.section("parts"));
        var in = new CheckpointIn(source, reading);
        var stored = new TreeMap<String, WorldPart>();
        int count = source.length(source.i(), 5);
        for(int i = 0; i < count; i++){
            Class<?> type = reading.type(source.i());
            if(!WorldPart.class.isAssignableFrom(type)) throw new CheckpointException(type.getName() + " is stored as a part but isn't one");
            int persistence = source.b();
            WorldPart part;
            if(persistence == Persistence.AUTHORITATIVE.ordinal() && SectionPart.class.isAssignableFrom(type)){
                var sectioned = (SectionPart)part(config, type);
                byte[] bytes = file.section(sectioned.section());
                var reads = new Reads(bytes);
                try{
                    sectioned.read(reading.world, reads);
                }catch(UncheckedIOException e){
                    throw new CheckpointException("section " + sectioned.section() + " ends early", e);
                }
                if(reads.remaining() != 0) throw new CheckpointException("section " + sectioned.section() + " has " + reads.remaining() + " unread bytes");
                part = sectioned;
            }else if(persistence == Persistence.AUTHORITATIVE.ordinal()){
                Plan plan = Codecs.plan(type);
                part = (WorldPart)plan.create();
                in.begin(reading.schema(type.getName()));
                plan.fill(part, in);
                in.end();
                reading.created(part, plan);
            }else if(persistence == Persistence.DERIVED.ordinal()){
                part = part(config, type);
            }else{
                throw new CheckpointException("part " + type.getName() + " has persistence " + persistence);
            }
            if(part.persistence().ordinal() != persistence){
                throw new CheckpointException("part " + type.getName() + " was stored as " + Persistence.values()[persistence] + " but is now " + part.persistence());
            }
            stored.put(type.getName(), part);
        }
        source.finish();
        var out = new ArrayList<WorldPart>();
        for(Class<? extends WorldPart> type : config.systems().parts()){
            WorldPart part = stored.remove(type.getName());
            if(part == null){
                part = config.systems().create(type);
                if(part.persistence() != Persistence.DERIVED){
                    throw new CheckpointException("the authoritative part " + type.getName() + " isn't in the checkpoint");
                }
            }
            if(part.persistence() == Persistence.DERIVED) derived.add(part);
            out.add(part);
        }
        if(!stored.isEmpty()) throw new CheckpointException("the checkpoint has parts this world doesn't run: " + stored.keySet());
        return out;
    }

    private CheckpointReader(){}
}
