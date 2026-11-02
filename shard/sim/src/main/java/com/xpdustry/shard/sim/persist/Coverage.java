package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * Checks that checkpoints cover the code (WAVES.md W1.4 "a reflection coverage test"): every instance field of a
 * stored class is written under its own name or marked {@link Derived}. A class's stored names come from writing a
 * blank instance with schema recording on, so the check sees exactly what a capture writes. The coverage test runs
 * {@link #classes} on every entity, part, controller and codec target on the classpath, and {@link #tables} on a
 * captured world.
 */
public final class Coverage{
    /**
     * World fields stored outside the {@code #world} section's own names, with where they go. A new {@code World}
     * field fails the check until it is listed here or stored.
     */
    private static final SortedMap<String, String> WORLD = sorted(
        "config", "#world (width, height, seed, inboxCapacity), #journal (kind, retention); the content, systems and kinds by the header's ids",
        "rand", "#world seed0, seed1",
        "timers", "#timers",
        "ids", "#world lastId",
        "tiles", "#tiles",
        "unitTable", "derived: read from the content",
        "buildings", "#buildings",
        "builds", "#builds",
        "units", "#units",
        "bullets", "#bullets",
        "members", "#members",
        "log", "#log position; entries since it go in crash bundles",
        "journal", "#journal",
        "parts", "parts",
        "inbox", "#inbox: the pending inputs by identity and the next arrival number; the rest is external",
        "subscribers", "external: observers subscribe again",
        "subscribing", "external: the subscription lock",
        "ticking", "derived: false at a tick boundary");

    /** The table classes, the section that stores each, and fields it stores under other names. */
    private static final List<Table> TABLES = List.of(
        new Table(World.class, "world", WORLD),
        new Table(Rand.class, "world", sorted()),
        new Table(EntityIds.class, "world", sorted()),
        new Table(TileGrid.class, "tiles", sorted()),
        new Table(Buildings.class, "buildings", sorted()),
        new Table(ActiveList.class, "builds", sorted()),
        new Table(ActiveList.class, "units", sorted()),
        new Table(Bullets.class, "bullets", sorted()),
        new Table(Timers.class, "timers", sorted()),
        new Table(Members.class, "members", sorted()),
        new Table(CommandLog.class, "log", sorted()),
        new Table(Inbox.class, "inbox", sorted(
            "queue", "arrivals (the numbers) and inputs",
            "arrivals", "nextArrival",
            "capacity", "#world inboxCapacity",
            "refused", "external: a counter for the runtime",
            "sealed", "external: the runtime seals a world it hibernates",
            "signal", "external: the runtime's wake-up")),
        new Table(MemoryJournal.class, "journal", sorted("entries", "the columns ticks … texts")));

    private record Table(Class<?> type, String section, SortedMap<String, String> elsewhere){
    }

    /**
     * What is wrong with how each class is stored, sorted; empty when every field is covered. Records, interfaces
     * and abstract classes are skipped: records store their components, the rest are checked through their concrete
     * subclasses.
     */
    public static List<String> classes(Collection<Class<?>> types){
        var problems = new TreeSet<String>();
        for(Class<?> type : types){
            if(type.isRecord() || type.isInterface() || Modifier.isAbstract(type.getModifiers()) || type.isEnum()) continue;
            problems.addAll(check(type));
        }
        return List.copyOf(problems);
    }

    /**
     * What is wrong with how {@code type} is stored. Derived parts store nothing: they are rebuilt. A
     * {@link SectionPart} stores its own format, which has no field names to match; its own tests compare a restored
     * part with the original instead.
     */
    public static List<String> check(Class<?> type){
        var problems = new ArrayList<String>();
        List<Schema.Field> stored;
        try{
            if(SectionPart.class.isAssignableFrom(type)) return problems;
            if(WorldPart.class.isAssignableFrom(type)
                && ((WorldPart)Codecs.construct(type)).persistence() == Persistence.DERIVED) return problems;
            stored = record(type);
        }catch(RuntimeException e){
            problems.add(type.getName() + ": a blank instance can't be written: " + e);
            return problems;
        }
        compare(type.getName(), fields(type), stored, sorted(), true, problems);
        return problems;
    }

    /** What is wrong with how a world's tables are stored, from a capture of it. */
    public static List<String> tables(Capture capture){
        var problems = new ArrayList<String>();
        var schemas = new TreeMap<String, Schema>();
        for(Schema schema : CheckpointWriter.readSchemas(capture.section("schema"))) schemas.put(schema.key(), schema);
        for(Table table : TABLES){
            Schema schema = schemas.get("#" + table.section());
            if(schema == null){
                problems.add("the capture has no #" + table.section() + " section schema");
                continue;
            }
            compare(table.type().getName() + " in #" + table.section(), fields(table.type()), schema.fields(), table.elsewhere(),
                false, problems);
        }
        return problems;
    }

    /** Authoritative attachment keys created so far that have no codec, or whose codec's fields don't match. */
    public static List<String> attachments(){
        var problems = new ArrayList<String>();
        for(AttachmentKey<?> key : Access.keys()){
            if(key.persistence() != Persistence.AUTHORITATIVE) continue;
            try{
                Codecs.attachment(key.name());
            }catch(CheckpointException e){
                problems.add(e.getMessage());
            }
        }
        return problems;
    }

    /** The fields {@code type} writes, from a blank instance. */
    static List<Schema.Field> record(Class<?> type){
        Plan plan = Codecs.plan(type);
        Object blank = plan.create();
        var capturing = new Capturing(null, true);
        var out = new CheckpointOut(new Sink(256), capturing);
        out.begin(capturing.schema(type));
        plan.write(blank, out);
        out.end();
        return capturing.schemas().get(capturing.classIndex(type)).fields();
    }

    /** Instance fields of {@code type} and its superclasses, minus synthetic and {@link Derived} ones, by name. */
    private static SortedMap<String, Field> fields(Class<?> type){
        var out = new TreeMap<String, Field>();
        for(Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()){
            for(Field field : at.getDeclaredFields()){
                if(Modifier.isStatic(field.getModifiers()) || field.isSynthetic() || field.isAnnotationPresent(Derived.class)) continue;
                Field shadowed = out.putIfAbsent(field.getName(), field);
                if(shadowed != null) out.put(field.getName() + " (" + at.getSimpleName() + ")", field);
            }
        }
        return out;
    }

    private static void compare(String what, SortedMap<String, Field> fields, List<Schema.Field> stored,
                                SortedMap<String, String> elsewhere, boolean strict, List<String> problems){
        var names = new TreeSet<String>();
        for(Schema.Field field : stored){
            if(!names.add(field.name())) problems.add(what + " writes " + field.name() + " twice");
        }
        for(var entry : fields.entrySet()){
            String name = entry.getKey();
            if(names.contains(name) || elsewhere.containsKey(name)) continue;
            Field field = entry.getValue();
            problems.add(what + ": field " + field.getDeclaringClass().getSimpleName() + "." + field.getName()
                + " is neither stored nor @Derived");
        }
        if(strict){
            for(String name : names){
                if(!fields.containsKey(name)) problems.add(what + " writes " + name + ", which is not one of its fields");
            }
        }
        for(String name : elsewhere.keySet()){
            if(!fields.containsKey(name)) problems.add(what + ": the coverage table lists " + name + ", which is gone");
        }
    }

    private static SortedMap<String, String> sorted(String... pairs){
        var out = new TreeMap<String, String>();
        for(int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]);
        return Collections.unmodifiableSortedMap(out);
    }

    private Coverage(){}
}
