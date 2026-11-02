package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;
import java.util.function.*;

/**
 * Captures a world into raw sections. The table sections store their columns as named fields under a section schema
 * ({@code #tiles}, {@code #bullets}…), named after the Java fields, so the coverage test checks them like classes.
 * The {@code objects} and {@code parts} sections hold one schema-checked record per object; a {@link SectionPart}
 * adds a section of its own.
 *
 * <p>Sections are written in an order that lets references discover objects: everything that can reference a world
 * object is written before {@code objects}, which writes the objects found on the way, and {@code types} and
 * {@code schema} come last.
 */
final class CheckpointWriter{
    static Capture capture(World world){
        if(!world.atBoundary()) throw new IllegalStateException("a checkpoint is taken between ticks");
        var capturing = new Capturing(world, false);
        var sections = new ArrayList<Container.Section>();
        TileGrid tiles = world.tiles();

        sections.add(section("world", 64, capturing, out -> {
            WorldConfig config = world.config();
            out.i("width", config.width()).i("height", config.height()).l("seed", config.seed())
                .i("inboxCapacity", config.inboxCapacity()).l("ticks", world.ticks())
                .l("seed0", world.rand().seed0).l("seed1", world.rand().seed1).bool("paused", world.paused());
            world.ids().write(out);
        }));
        sections.add(section("tiles", tiles.size() * 18 + 128, capturing, tiles::write));
        sections.add(section("buildings", world.buildings().indexLimit() * 4 + 256, capturing, world.buildings()::write));
        sections.add(section("builds", world.builds().size() * 4 + 64, capturing, out -> list(world.builds(), out, capturing)));
        sections.add(section("units", world.units().size() * 4 + 64, capturing, out -> list(world.units(), out, capturing)));
        sections.add(section("bullets", world.bullets().size() * 48 + 128, capturing, world.bullets()::write));
        sections.add(section("timers", 64, capturing, out -> {
            Timers timers = world.timers();
            List<Timers.Pending> pending = timers.pending();
            var delays = new float[pending.size()];
            var tasks = new ArrayList<Timers.Task>(pending.size());
            for(int i = 0; i < delays.length; i++){
                delays[i] = pending.get(i).delay();
                tasks.add(pending.get(i).task());
            }
            out.d("time", timers.rawTime()).floats("runDelays", delays).list("runs", tasks);
        }));
        sections.add(section("members", 64, capturing, out -> {
            List<Long> sessions = world.members().sessions();
            var ids = new long[sessions.size()];
            var routes = new int[ids.length];
            var sequences = new long[ids.length];
            for(int i = 0; i < ids.length; i++){
                Members.Member member = world.members().get(sessions.get(i)).orElseThrow();
                ids[i] = sessions.get(i);
                routes[i] = member.routeGeneration();
                sequences[i] = member.lastSequence();
            }
            out.longs("members", ids).ints("routeGenerations", routes).longs("lastSequences", sequences);
        }));
        sections.add(section("log", 32, capturing, out -> out.implicit("entries").l("position", world.log().position())));
        sections.add(section("inbox", 64, capturing, out -> inbox(world.inbox().snapshot(), out, capturing)));
        sections.add(section("journal", world.journal().entries().size() * 40 + 128, capturing, out -> journal(world.journal(), out)));
        parts(world, capturing, sections);
        sections.add(objects(capturing));
        sections.add(section("types", capturing.objectCount() * 8 + 64, capturing, out -> types(out, capturing)));
        long schemaId = schemaId(capturing.schemas());
        sections.add(schemaSection(capturing.schemas()));

        var header = new Header("checkpoint", BuildInfo.id(), schemaId, world.content().hash(), Checkpoint.systemsId(world.config()),
            world.ticks(), new TreeMap<>());
        return new Capture(header, sections, world.log().position());
    }

    private static Container.Section section(String name, int capacity, Capturing capturing, Consumer<CheckpointOut> body){
        var sink = new Sink(capacity);
        var out = new CheckpointOut(sink, capturing);
        out.begin(capturing.section(name));
        body.accept(out);
        out.end();
        return new Container.Section(name, sink.buffer(), sink.size());
    }

    /** An {@code ActiveList}, as object indexes in order. */
    private static void list(ActiveList<?> list, CheckpointOut out, Capturing capturing){
        var items = (Entity[])Access.LIST_ITEMS.get(list);
        int size = list.size();
        var indexes = new int[size];
        for(int i = 0; i < size; i++) indexes[i] = capturing.indexOf(items[i]);
        out.implicit("slot").i("capacity", items.length).ints("items", indexes).i("size", size)
            .i("index", (int)Access.LIST_INDEX.get(list)).derived("clearing");
    }

    /**
     * The inputs waiting in the inbox, with their arrival numbers, and the next number (W1.4): a restored world admits
     * them at its next tick and goes on numbering where this one was. Inputs are made outside the world, so they hold
     * world objects by identity, which also lets a fork's capture store the live world's inbox.
     */
    private static void inbox(Inbox.Snapshot inbox, CheckpointOut out, Capturing capturing){
        List<Inbox.Arrival> pending = inbox.pending();
        var numbers = new long[pending.size()];
        var inputs = new ArrayList<Input>(numbers.length);
        for(int i = 0; i < numbers.length; i++){
            numbers[i] = pending.get(i).number();
            inputs.add(pending.get(i).input());
        }
        capturing.identities = true;
        try{
            out.l("nextArrival", inbox.nextArrival()).longs("arrivals", numbers).list("inputs", inputs);
        }finally{
            capturing.identities = false;
        }
    }

    /** Journal entries as columns; causes as a kind, a number and an index into a table of distinct texts. */
    private static void journal(Journal journal, CheckpointOut out){
        List<Journal.Entry> entries = journal.entries();
        int n = entries.size();
        var ticks = new long[n];
        var pos = new int[n];
        var floor = new short[n];
        var overlay = new short[n];
        var block = new short[n];
        var team = new byte[n];
        var build = new int[n];
        var data = new byte[n];
        var floorData = new byte[n];
        var overlayData = new byte[n];
        var extraData = new int[n];
        var kinds = new byte[n];
        var numbers = new long[n];
        var texts = new int[n];
        var table = new TreeMap<String, Integer>();
        var ordered = new ArrayList<String>();
        for(int i = 0; i < n; i++){
            Journal.Entry entry = entries.get(i);
            TileImage before = entry.before();
            ticks[i] = entry.tick();
            pos[i] = before.pos();
            floor[i] = before.floor();
            overlay[i] = before.overlay();
            block[i] = before.block();
            team[i] = before.team();
            build[i] = before.build();
            data[i] = before.data();
            floorData[i] = before.floorData();
            overlayData[i] = before.overlayData();
            extraData[i] = before.extraData();
            String text = null;
            switch(entry.cause()){
                case Cause.Server ignored -> kinds[i] = 0;
                case Cause.Player player -> {
                    kinds[i] = 1;
                    numbers[i] = player.session();
                }
                case Cause.Team t -> {
                    kinds[i] = 2;
                    numbers[i] = t.team();
                }
                case Cause.Admin admin -> {
                    kinds[i] = 3;
                    text = admin.name();
                }
                case Cause.Ai ai -> {
                    kinds[i] = 4;
                    numbers[i] = ai.team();
                }
                case Cause.Plugin plugin -> {
                    kinds[i] = 5;
                    text = plugin.id();
                }
            }
            if(text == null){
                texts[i] = -1;
            }else{
                Integer index = table.get(text);
                if(index == null){
                    index = ordered.size();
                    table.put(text, index);
                    ordered.add(text);
                }
                texts[i] = index;
            }
        }
        var lengths = new int[ordered.size()];
        var joined = new StringBuilder();
        for(int i = 0; i < lengths.length; i++){
            lengths[i] = ordered.get(i).length();
            joined.append(ordered.get(i));
        }
        int kind = journal == Journal.discarding() ? 0 : journal instanceof MemoryJournal ? 1 : 2;
        int retention = journal instanceof MemoryJournal memory ? memory.retention() : 0;
        out.b("kind", kind).i("retention", retention).l("start", journal.position() - n);
        out.longs("ticks", ticks).ints("pos", pos).shorts("floor", floor).shorts("overlay", overlay).shorts("block", block)
            .bytes("team", team).ints("build", build).bytes("data", data).bytes("floorData", floorData)
            .bytes("overlayData", overlayData).ints("extraData", extraData);
        out.bytes("causeKinds", kinds).longs("causeNumbers", numbers).ints("causeTexts", texts)
            .ints("textLengths", lengths).str("texts", joined.toString());
    }

    /**
     * Each part: its class and persistence, then, if authoritative, its fields; an authoritative {@link SectionPart}
     * writes its own section instead, after this one.
     */
    private static void parts(World world, Capturing capturing, List<Container.Section> sections){
        var sink = new Sink(256);
        var out = new CheckpointOut(sink, capturing);
        var own = new ArrayList<Container.Section>();
        var taken = new TreeSet<>(List.of("parts", "objects", "types", "schema"));
        for(Container.Section section : sections) taken.add(section.name());
        List<WorldPart> parts = world.parts();
        sink.i(parts.size());
        for(WorldPart part : parts){
            sink.i(capturing.classIndex(part.getClass()));
            Persistence persistence = part.persistence();
            if(persistence == Persistence.EXTERNAL) throw new CheckpointException(part.getClass().getName() + " is a part but says it is external");
            sink.b(persistence.ordinal());
            if(persistence != Persistence.AUTHORITATIVE) continue;
            if(part instanceof SectionPart sectioned){
                var writes = new Writes();
                sectioned.write(world, writes);
                String name = sectioned.section();
                if(!taken.add(name)) throw new CheckpointException(part.getClass().getName() + "'s section " + name + " is taken");
                own.add(new Container.Section(name, writes.buffer(), writes.size()));
            }else{
                Plan plan = Codecs.plan(part.getClass());
                out.begin(capturing.schema(part.getClass()));
                plan.write(part, out);
                out.end();
            }
        }
        sections.add(new Container.Section("parts", sink.buffer(), sink.size()));
        sections.addAll(own);
    }

    /** Every world object, in index order: the registry, the unit list, then whatever was referenced on the way. */
    private static Container.Section objects(Capturing capturing){
        var sink = new Sink(capturing.high() * 96 + capturing.unitCount() * 64 + 256);
        var out = new CheckpointOut(sink, capturing);
        int fixed = capturing.high() + capturing.unitCount();
        for(int index = 0; index < fixed; index++){
            Object value = capturing.object(index);
            if(value != null) object(value, out, capturing);
        }
        for(int i = 0; i < capturing.extraCount(); i++) object(capturing.extra(i), out, capturing);
        return new Container.Section("objects", sink.buffer(), sink.size());
    }

    private static void object(Object value, CheckpointOut out, Capturing capturing){
        Plan plan = Codecs.plan(value.getClass());
        out.begin(capturing.schema(value.getClass()));
        plan.write(value, out);
        out.end();
    }

    /** Each object's class (-1 for an empty registry slot) and the content it is made from. */
    private static void types(CheckpointOut out, Capturing capturing){
        int count = capturing.objectCount();
        var classes = new int[count];
        var defs = new int[count];
        for(int i = 0; i < count; i++){
            Object value = capturing.object(i);
            if(value == null){
                classes[i] = -1;
                defs[i] = -1;
                continue;
            }
            classes[i] = capturing.classIndex(value.getClass());
            defs[i] = switch(value){
                case Building building -> building.block().id();
                case Unit unit -> unit.type().id();
                default -> -1;
            };
        }
        out.i("count", count).ints("classes", classes).ints("defs", defs);
    }

    static Container.Section schemaSection(List<Schema> schemas){
        var sink = new Sink(schemas.size() * 128);
        sink.i(schemas.size());
        for(Schema schema : schemas){
            sink.str(schema.key());
            sink.i(schema.fields().size());
            for(Schema.Field field : schema.fields()){
                sink.str(field.name());
                sink.b(field.type().ordinal());
            }
        }
        return new Container.Section("schema", sink.buffer(), sink.size());
    }

    static List<Schema> readSchemas(byte[] bytes){
        var source = new Source("schema", bytes);
        int count = source.length(source.i(), 8);
        var out = new ArrayList<Schema>(count);
        for(int i = 0; i < count; i++){
            String key = source.str();
            int fields = source.length(source.i(), 5);
            var list = new ArrayList<Schema.Field>(fields);
            for(int f = 0; f < fields; f++){
                String name = source.str();
                if(name == null) throw source.corrupt("a field has no name");
                list.add(new Schema.Field(name, FieldType.of(source.b())));
            }
            if(key == null) throw source.corrupt("a schema has no key");
            out.add(new Schema(key, list));
        }
        source.finish();
        return out;
    }

    /** A hash of every key, field name and type, in table order. */
    static long schemaId(List<Schema> schemas){
        var hasher = new Hasher();
        for(Schema schema : schemas){
            hasher.str(schema.key()).i(schema.fields().size());
            for(Schema.Field field : schema.fields()) hasher.str(field.name()).i(field.type().ordinal());
        }
        return hasher.value();
    }

    private CheckpointWriter(){}
}
