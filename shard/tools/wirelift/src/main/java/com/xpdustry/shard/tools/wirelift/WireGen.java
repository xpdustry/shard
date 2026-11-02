package com.xpdustry.shard.tools.wirelift;

import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Compiles a wire schema into Java codecs over {@code :wire}'s runtime (ported from proto 25's {@code WireGen}). Nothing
 * generated references vanilla. Per package it writes:
 * <ul>
 * <li>one enum per schema enum, with a range-checked {@code of(ordinal)};
 * <li>{@code <Layout>Layout}: a {@code Source}/{@code Sink} interface pair that {@code :compat} adapts its unit
 *     projections to, {@code writeSync}/{@code readSync}, {@code writeSave}/{@code readSave} (every revision vanilla
 *     reads, old saves' included) and a plain {@code State};
 * <li>{@code EntityClasses}: the {@code EntityMapping} table and dispatch by class id, payloads, unit containers and
 *     one {@code is<Type>} check per entity cast;
 * <li>{@code Packets}: the registry (ids, direction, priority, transport) and one record per {@code Call} packet with
 *     its writer and reader.
 * </ul>
 * It refuses a schema with problems, a codec it has no mapping for and any packet layout but plain fields with the
 * sender guard of both-way packets.
 */
final class WireGen{
    /**
     * How a wire codec maps to Java. {@code %s} is the value; the generated code names its {@code Writes} {@code write},
     * its {@code Reads} {@code read} and its {@code WireContext} {@code context}.
     * @param init the value a fresh {@code State} holds, or null for Java's default
     */
    record Codec(String type, String write, String read, String init){}

    static final Map<String, Codec> CODECS = new LinkedHashMap<>();

    static{
        codec("bool", "boolean", "write.bool(%s)", "read.bool()", null);
        codec("i8", "byte", "write.b(%s)", "read.b()", null);
        codec("i16", "short", "write.s(%s)", "read.s()", null);
        codec("i32", "int", "write.i(%s)", "read.i()", null);
        codec("i64", "long", "write.l(%s)", "read.l()", null);
        codec("f32", "float", "write.f(%s)", "read.f()", null);
        codec("f64", "double", "write.d(%s)", "read.d()", null);
        codec("String", "String", "Codecs.writeString(write, %s)", "Codecs.readString(read)", null);
        //entity, building and tile references: an id or packed position, -1 for none
        for(String ref : List.of("Entity", "PosEntity", "Building", "Tile")){
            codec(ref, "int", "write.i(%s)", "read.i()", "-1");
        }
        codec("Unit", "Codecs.UnitRef", "Codecs.writeUnit(write, %s)", "Codecs.readUnit(read)", "Codecs.UnitRef.NONE");
        codec("Team", "int", "Codecs.writeTeam(write, %s)", "Codecs.readTeam(read)", null);
        codec("Color", "int", "write.i(%s)", "read.i()", "-1");
        //content ids: an i16, -1 for none where vanilla allows it
        for(String content : List.of("Block", "Item", "Liquid", "Weather", "UnitType", "BulletType", "Sound")){
            codec(content, "short", "write.s(%s)", "read.s()", "-1");
        }
        for(String content : List.of("ref<block>", "ref<liquid>", "ref<weather>")){
            codec(content, "short", "write.s(%s)", "read.s()", "0");
        }
        //a unit's type: vanilla's setType fails on an unknown one
        codec("ref<unit>", "short", "write.s(%s)", "Codecs.readUnitType(read, context)", "0");
        //Effect.get(read.us()): written signed, read unsigned
        codec("Effect", "int", "write.s(%s)", "read.us()", null);
        codec("Command", "int", "Codecs.writeCommand(write, %s)", "Codecs.readCommand(read)", "Codecs.NO_COMMAND");
        codec("Stance", "int", "Codecs.writeCommand(write, %s)", "Codecs.readCommand(read)", "Codecs.NO_COMMAND");
        codec("Action", "AdminAction", "write.b(%s.ordinal())", "AdminAction.of(read.b())", null);
        codec("Kick", "KickReason", "write.b(%s.ordinal())", "KickReason.of(read.b())", null);
        codec("MarkerControl", "LMarkerControl", "write.b(%s.ordinal())", "LMarkerControl.of(read.ub())", null);
        codec("Content", "WireObjects.ContentRef", "Codecs.writeContent(write, %s)", "Codecs.readContent(read)", null);
        codec("Bytes", "byte[]", "Codecs.writeBytes(write, %s)", "Codecs.readBytes(read)", null);
        codec("Ints", "int[]", "Codecs.writeInts(write, %s)", "Codecs.readInts(read)", null);
        codec("Shorts", "short[]", "Codecs.writeShorts(write, %s)", "Codecs.readShorts(read)", null);
        codec("IntSeq", "int[]", "Codecs.writeIntSeq(write, %s)", "Codecs.readIntSeq(read)", null);
        codec("StringArray", "String[][]", "Codecs.writeStringArray(write, %s)", "Codecs.readStringArray(read)", null);
        codec("ItemStacks", "List<Codecs.ItemStack>", "Codecs.writeItemStacks(write, %s)", "Codecs.readItemStacks(read)", null);
        codec("LiquidStacks", "List<Codecs.LiquidStack>", "Codecs.writeLiquidStacks(write, %s)", "Codecs.readLiquidStacks(read)", null);
        codec("Object", "Object", "WireObjects.writeObject(write, %s)", "WireObjects.readObject(read, context)", null);
        codec("ObjectSafe", "Object", "WireObjects.writeObjectSafe(write, %s)", "WireObjects.readObjectSafe(read, context)", null);
        codec("ClientPlans", "List<Codecs.ClientPlan>", "Codecs.writeClientPlans(write, %s, context)", "Codecs.readClientPlans(read, context)", null);
        codec("PlansQueueNet", "List<Codecs.Plan>", "Codecs.writePlansQueueNet(write, %s)", "Codecs.readPlansQueueNet(read, context)", "new ArrayList<>()");
        codec("Plan", "Codecs.Plan", "Codecs.writePlan(write, %s)", "Codecs.readPlan(read, context)", null);
        codec("MenuResult", "Codecs.MenuResult", "Codecs.writeMenuResult(write, %s)", "Codecs.readMenuResult(read)", null);
        codec("UiBuilder", "Codecs.UiNode", "Codecs.writeUiBuilder(write, %s)", "Codecs.readUiBuilder(read)", null);
        codec("TraceInfo", "Codecs.TraceInfo", "Codecs.writeTraceInfo(write, %s)", "Codecs.readTraceInfo(read)", null);
        codec("Rules", "String", "Codecs.writeJson(write, %s)", "Codecs.readRules(read)", null);
        codec("Objectives", "String", "Codecs.writeJson(write, %s)", "Codecs.readObjectives(read)", null);
        codec("ObjectiveMarker", "String", "Codecs.writeJson(write, %s)", "Codecs.readObjectiveMarker(read)", null);
        codec("UnitContainer", "Codecs.SyncedUnit", "EntityClasses.writeSyncedUnit(write, %s, context)", "EntityClasses.readSyncedUnit(read, context)", null);
        codec("Abilities", "float[]", "Codecs.writeAbilities(write, %s)", "Codecs.readAbilities(read)", "new float[0]");
        codec("Controller", "Codecs.Controller", "Codecs.writeController(write, %s)", "Codecs.readController(read)", "Codecs.OtherControl.GENERIC");
        codec("Mounts", "List<Codecs.Mount>", "Codecs.writeMounts(write, %s)", "Codecs.readMounts(read)", "List.of()");
        codec("Items", "Codecs.ItemStack", "Codecs.writeItems(write, %s)", "Codecs.readItems(read)", "new Codecs.ItemStack(-1, 0)");
        codec("Vec2", "Codecs.Vec2", "Codecs.writeVec2(write, %s)", "Codecs.readVec2(read)", "Codecs.Vec2.ZERO");
        codec("Status", "Codecs.StatusEntry", "Codecs.writeStatus(write, %s, context)", "Codecs.readStatus(read, context)", null);
        codec("Payload", "Codecs.Payload", "EntityClasses.writePayload(write, %s, context)", "EntityClasses.readPayload(read, context)", null);
    }

    static void codec(String name, String type, String write, String read, String init){
        CODECS.put(name, new Codec(type, write, read, init));
    }

    /** Names the generated methods use for parameters and locals, which fields must not take. */
    static final Set<String> LAYOUT_RESERVED = Set.of("write", "read", "context", "local", "source", "sink", "value", "item", "count", "i"),
        PACKET_RESERVED = Set.of("write", "read", "context", "fromServer", "ID");

    static final Pattern FIELD = Pattern.compile("(\\??)(\\w+(?:<\\w+>)?) (\\w+)"), CAST = Pattern.compile("Entity<(\\w+)>");
    static final String GUARD = "[if net.server() eq]";

    final Schema schema;
    final String pkg;
    final String header;

    private WireGen(Schema schema, String pkg){
        this.schema = schema;
        this.pkg = pkg;
        this.header = "// Generated by :tools:wirelift (WireGen) from wire/schema/v" + schema.build() + ".yaml. Do not edit:\n"
            + "// run ./gradlew :tools:wirelift:generateWire.\npackage " + pkg + ";\n\n";
    }

    /** Generates the package's sources: file name to content. */
    static Map<String, String> generate(Schema schema, String pkg){
        if(!schema.problems().isEmpty()) throw new IllegalArgumentException("the schema has problems; fix the lifter first: " + schema.problems());
        WireGen gen = new WireGen(schema, pkg);
        Map<String, String> files = new TreeMap<>();
        files.put("package-info.java", gen.packageInfo());
        for(var e : schema.enums().entrySet()) files.put(e.getKey() + ".java", gen.enumeration(e.getKey(), e.getValue()));
        for(var layout : schema.layouts().values()) files.put(layout.name() + "Layout.java", gen.layout(layout));
        files.put("EntityClasses.java", gen.entityClasses());
        files.put("Packets.java", gen.packets());
        return files;
    }

    //region package and enums

    String packageInfo(){
        return "// Generated by :tools:wirelift (WireGen) from wire/schema/v" + schema.build() + ".yaml. Do not edit:\n"
            + "// run ./gradlew :tools:wirelift:generateWire.\n"
            + "/**\n"
            + " * The wire codecs of Mindustry build " + schema.build() + ", generated from the lifted schema: the packet registry and\n"
            + " * records ({@link " + pkg + ".Packets}), the entity class table ({@link " + pkg + ".EntityClasses}), one\n"
            + " * {@code <Layout>Layout} per entity sync and save layout, and the enums the wire carries as ordinals. The codecs\n"
            + " * they call are hand-written in {@link com.xpdustry.shard.wire}.\n"
            + " */\n"
            + "package " + pkg + ";\n";
    }

    String enumeration(String name, List<String> constants){
        for(String c : constants){
            if(!SourceVersionCheck.identifier(c)) throw new IllegalArgumentException(name + "." + c + " is not a Java identifier");
        }
        Src s = new Src(header);
        s.line("import com.xpdustry.shard.wire.*;").blank();
        s.line("/** Vanilla's {@code " + name + "}, which the wire carries as its ordinal. */");
        s.open("public enum " + name);
        s.wrapped(constants, ";");
        s.blank();
        s.line("private static final " + name + "[] all = values();").blank();
        s.line("/** The constant with this ordinal; vanilla's {@code all[ordinal]} throws for any other. */");
        s.open("public static " + name + " of(int ordinal)");
        s.line("if(ordinal < 0 || ordinal >= all.length) throw new WireException(\"" + name + " \" + ordinal + \" out of range\");");
        s.line("return all[ordinal];");
        s.close();
        s.close();
        return s.toString();
    }

    //endregion
    //region layouts

    /** A layout field resolved to Java. */
    record Member(String name, String type, Codec element, Codec count, boolean seq, boolean local, String note){}

    Member member(Schema.Field f, String where){
        if(LAYOUT_RESERVED.contains(f.name())) throw new IllegalArgumentException(where + ": field name " + f.name() + " is reserved");
        String note = (f.local() ? "@SyncLocal" : "") + (f.interp() != null ? (f.local() ? ", " : "") + "interpolated (" + f.interp() + ")" : "");
        if(f.codec().startsWith("seq<")){
            Codec element = codec(f.codec().substring(4, f.codec().length() - 1), where);
            if(f.count() == null) throw new IllegalArgumentException(where + ": " + f.name() + " has no count codec");
            return new Member(f.name(), "List<" + box(element.type) + ">", element, codec(f.count(), where), true, f.local(), note);
        }
        Codec c = codec(f.codec(), where);
        return new Member(f.name(), c.type, c, null, false, f.local(), note);
    }

    Codec codec(String name, String where){
        Matcher cast = CAST.matcher(name);
        if(cast.matches()){
            //an entity id that may only name an entity of the cast's type
            String type = cast.group(1);
            if(!schema.casts().containsKey(type)) throw new IllegalArgumentException(where + ": no entity classes for cast " + type);
            return new Codec("int", "write.i(%s)", "Codecs.readEntity(read, EntityClasses::is" + type + ", \"" + type + "\", context)", "-1");
        }
        Codec c = CODECS.get(name);
        if(c == null) throw new IllegalArgumentException(where + ": no Java mapping for codec " + name);
        return c;
    }

    String layout(Schema.Layout layout){
        String name = layout.name() + "Layout";
        List<Member> sync = layout.sync() == null ? null : layout.sync().stream().map(f -> member(f, layout.name() + ".sync")).toList();
        List<Member> save = layout.save() == null ? null : layout.save().stream().map(f -> member(f, layout.name() + ".save")).toList();
        //one accessor per field name across both bodies, so the same name must have the same Java type
        Map<String, Member> members = new TreeMap<>();
        for(List<Member> body : Arrays.asList(sync, save)){
            if(body == null) continue;
            for(Member m : body){
                Member prior = members.putIfAbsent(m.name, m);
                if(prior != null && !prior.type.equals(m.type)){
                    throw new IllegalArgumentException(layout.name() + "." + m.name + " is " + prior.type + " in one body and " + m.type + " in the other");
                }
            }
        }
        String classes = schema.entities().stream().filter(e -> layout.name().equals(e.layout()))
            .map(e -> e.className() + " (" + e.id() + ")").collect(Collectors.joining(", "));

        Src s = new Src(header);
        s.line("import com.xpdustry.shard.common.io.*;").line("import com.xpdustry.shard.wire.*;").blank().line("import java.util.*;").blank();
        s.line("/**");
        s.line(" * Entity layout {@code " + layout.name() + "}" + (sync != null ? ": {@code writeSync}" : ": no sync") + (save != null ? " and {@code write}" : "")
            + " of " + classes + ".");
        if(save != null){
            s.line(" * A save body follows the class's i16 revision, which {@link EntityClasses} writes and reads. Old saves carry older");
            s.line(" * revisions, which {@link #readSave(Reads, Sink, int, int, WireContext)} also reads.");
        }
        s.line(" */");
        s.open("public final class " + name);
        s.line("private " + name + "(){}").blank();

        s.line("/** What the encoders read. {@code :compat} adapts its frozen projections to it. */");
        s.open("public interface Source extends WireEntity");
        for(Member m : members.values()){
            s.line(m.type + " " + m.name + "();" + (m.note.isEmpty() ? "" : " //" + m.note));
        }
        s.close().blank();

        s.line("/** Where the decoders put what they read. */");
        s.open("public interface Sink");
        for(Member m : members.values()) s.line("void " + m.name + "(" + m.type + " value);");
        s.close();

        if(sync != null){
            s.blank().line("/** {@code writeSync}, in wire order. */");
            s.open("public static void writeSync(Writes write, Source source, WireContext context)");
            for(Member m : sync) writeMember(s, m);
            s.close().blank();
            s.line("/**");
            s.line(" * {@code readSync}.");
            s.line(" * @param local the entity is the reader's own ({@code isLocal()}): @SyncLocal fields are read and dropped");
            s.line(" */");
            s.open("public static void readSync(Reads read, Sink sink, boolean local, WireContext context)");
            for(Member m : sync) readMember(s, m, true);
            s.close();
        }
        if(save != null){
            s.blank().line("/** {@code write} after the revision, in wire order. */");
            s.open("public static void writeSave(Writes write, Source source, WireContext context)");
            for(Member m : save) writeMember(s, m);
            s.close().blank();
            s.line("/** {@code read} of the current revision, after the revision. */");
            s.open("public static void readSave(Reads read, Sink sink, WireContext context)");
            for(Member m : save) readMember(s, m, false);
            s.close();
            revisions(s, layout, members);
        }

        s.blank().line("/** A plain holder, for tests and tools. It starts as vanilla's constructor leaves an entity. */");
        s.open("public static final class State implements Source, Sink");
        for(Member m : members.values()){
            String lifted = layout.defaults().get(m.name);
            String init = lifted != null ? literal(m.type, lifted) : m.seq ? "new ArrayList<>()" : m.element.init;
            s.line("public " + m.type + " " + m.name + (init == null ? "" : " = " + init) + ";");
        }
        for(Member m : members.values()){
            s.blank().line("@Override");
            s.open("public " + m.type + " " + m.name + "()").line("return " + m.name + ";").close();
            s.blank().line("@Override");
            s.open("public void " + m.name + "(" + m.type + " value)").line(m.name + " = value;").close();
        }
        s.close();
        s.close();
        return s.toString();
    }

    /** A lifted default as a Java literal of {@code type}. */
    static String literal(String type, String value){
        return switch(type){
            case "boolean" -> value;
            case "byte", "short" -> "(" + type + ")" + value;
            case "int" -> value;
            case "float" -> value + "f";
            case "double" -> value + (value.contains(".") ? "" : ".0");
            default -> throw new IllegalArgumentException("a lifted default " + value + " for a field of type " + type);
        };
    }

    /**
     * The revision dispatch of a saved layout and one reader per old body (EntityIO.java:99): an old body reads fields
     * the layout no longer has and drops them, and leaves the fields it lacks as the sink has them.
     */
    void revisions(Src s, Schema.Layout layout, Map<String, Member> members){
        //classes with the same revisions share a case
        Map<String, List<Schema.Entity>> groups = new LinkedHashMap<>();
        Map<String, List<String>> users = new TreeMap<>();
        for(Schema.Entity e : schema.entities()){
            if(!layout.name().equals(e.layout())) continue;
            groups.computeIfAbsent(e.revision() + " " + e.old(), k -> new ArrayList<>()).add(e);
            e.old().forEach((revision, body) -> users.computeIfAbsent(body, k -> new ArrayList<>()).add(e.className() + " " + revision));
        }
        s.blank().line("/**");
        s.line(" * {@code read} after the revision, for any revision vanilla reads: the current one, or an older one from an old save.");
        s.line(" * Fields an old revision lacks keep the value the sink has; those the layout no longer has are read and dropped.");
        s.line(" * @throws WireException for a revision vanilla doesn't know, as its {@code read} throws");
        s.line(" */");
        s.open("public static void readSave(Reads read, Sink sink, int classId, int revision, WireContext context)");
        s.open("switch(classId)");
        for(List<Schema.Entity> group : groups.values()){
            Schema.Entity first = group.getFirst();
            Map<String, List<Integer>> bodies = new LinkedHashMap<>();
            bodies.put(Schema.SAVE, new ArrayList<>(List.of(first.revision())));
            first.old().forEach((revision, body) -> bodies.computeIfAbsent(body, k -> new ArrayList<>()).add(revision));
            s.open("case " + ids(group.stream().map(Schema.Entity::id).toList()) + " ->");
            s.open("switch(revision)");
            bodies.forEach((body, revisions) -> {
                if(!body.equals(Schema.SAVE) && !layout.old().containsKey(body)) throw new IllegalArgumentException(first.className() + ": no old body " + body + " in layout " + layout.name());
                s.line("case " + ids(revisions) + " -> " + (body.equals(Schema.SAVE) ? "readSave" : oldReader(body)) + "(read, sink, context);");
            });
            s.line("default -> throw new WireException(EntityClasses.get(classId).name() + \" has no revision \" + revision);");
            s.close().close();
        }
        s.line("default -> throw new WireException(\"entity class \" + classId + \" is not saved with layout " + layout.name() + "\");");
        s.close().close();
        for(var body : layout.old().entrySet()){
            if(!users.containsKey(body.getKey())) throw new IllegalArgumentException(layout.name() + ": old body " + body.getKey() + " has no revision");
            s.blank().line("/** {@code read} of an old revision, after the revision: " + String.join(", ", users.get(body.getKey())) + ". */");
            s.open("private static void " + oldReader(body.getKey()) + "(Reads read, Sink sink, WireContext context)");
            for(Schema.Field f : body.getValue()){
                String where = layout.name() + "." + body.getKey();
                boolean dropped = f.name().equals("_") || !members.containsKey(f.name());
                Member m = member(dropped ? new Schema.Field("dropped", f.codec(), f.count(), false, null) : f, where);
                if(dropped){
                    dropMember(s, m);
                }else if(!members.get(f.name()).type.equals(m.type)){
                    throw new IllegalArgumentException(where + "." + f.name() + " is " + m.type + ", but the layout has " + members.get(f.name()).type);
                }else{
                    readMember(s, m, false);
                }
            }
            s.close();
        }
    }

    /** {@code MechUnit.2} → {@code readMechUnitRevision2}. */
    static String oldReader(String body){
        int dot = body.lastIndexOf('.');
        if(dot < 0 || !SourceVersionCheck.identifier(body.substring(0, dot)) || !body.substring(dot + 1).matches("\\d+")){
            throw new IllegalArgumentException("old body names are <class>.<revision>, not " + body);
        }
        return "read" + body.substring(0, dot) + "Revision" + body.substring(dot + 1);
    }

    /** Reads a field an old revision has and the layout doesn't, for nothing. */
    void dropMember(Src s, Member m){
        if(m.seq){
            s.open("").line("int count = " + m.count.read + ";").line("for(int i = 0; i < count; i++) " + m.element.read + ";").close();
        }else{
            s.line(m.element.read + ";");
        }
    }

    void writeMember(Src s, Member m){
        String value = "source." + m.name + "()";
        if(m.seq){
            s.line(statement(m.count.write, value + ".size()"));
            s.line("for(var item : " + value + ") " + statement(m.element.write, "item"));
        }else{
            s.line(statement(m.element.write, value));
        }
    }

    void readMember(Src s, Member m, boolean sync){
        boolean drop = sync && m.local;
        if(m.seq){
            //a negative count reads nothing, and readers that return null (plans off the world, absent payloads) are skipped
            s.open("");
            s.line("int count = " + m.count.read + ";");
            s.line(m.type + " value = new ArrayList<>();");
            s.open("for(int i = 0; i < count; i++)");
            s.line(box(m.element.type) + " item = " + m.element.read + ";");
            s.line("if(item != null) value.add(item);");
            s.close();
            s.line((drop ? "if(!local) " : "") + "sink." + m.name + "(value);");
            s.close();
        }else if(drop){
            s.open("").line(m.type + " value = " + m.element.read + ";").line("if(!local) sink." + m.name + "(value);").close();
        }else{
            s.line("sink." + m.name + "(" + m.element.read + ");");
        }
    }

    static String statement(String template, String value){
        return template.formatted(value) + ";";
    }

    static String box(String type){
        return switch(type){
            case "boolean" -> "Boolean";
            case "byte" -> "Byte";
            case "short" -> "Short";
            case "int" -> "Integer";
            case "long" -> "Long";
            case "float" -> "Float";
            case "double" -> "Double";
            default -> type;
        };
    }

    //endregion
    //region entity classes

    String entityClasses(){
        String blockUnit = schema.entities().stream().filter(e -> e.className().equals("BlockUnitUnit")).findFirst()
            .map(e -> constant(e.className())).orElseThrow(() -> new IllegalArgumentException("no BlockUnitUnit class"));
        Src s = new Src(header);
        s.line("import com.xpdustry.shard.common.io.*;").line("import com.xpdustry.shard.wire.*;").blank().line("import java.util.*;").blank();
        s.line("/**");
        s.line(" * {@code EntityMapping.idMap}: the class id is the u8 before each entity in a snapshot, the first byte of an entity");
        s.line(" * save chunk and the byte after a unit payload's type. Dispatches each class to its layout.");
        s.line(" */");
        s.open("public final class EntityClasses");
        s.line("private EntityClasses(){}").blank();
        s.line("/**");
        s.line(" * One class.");
        s.line(" * @param layout   its {@code <Layout>Layout}, or null when it is neither synced nor saved");
        s.line(" * @param synced   its layout has a sync body");
        s.line(" * @param revision the i16 its save body starts with, or -1 without one");
        s.line(" * @param unit     a {@code Unit}: it may be a unit payload");
        s.line(" */");
        s.line("public record EntityClass(int id, String name, String layout, boolean synced, int revision, boolean serialize, boolean unit){}").blank();
        for(Schema.Entity e : schema.entities()) s.line("public static final int " + constant(e.className()) + " = " + e.id() + ";");
        s.blank().line("/** Every class, in id order. */");
        s.line("public static final List<EntityClass> ALL = List.of(");
        List<String> rows = new ArrayList<>();
        for(Schema.Entity e : schema.entities()){
            boolean synced = e.layout() != null && schema.layouts().get(e.layout()).sync() != null;
            rows.add("new EntityClass(" + e.id() + ", \"" + e.className() + "\", " + (e.layout() == null ? "null" : "\"" + e.layout() + "\"") + ", "
                + synced + ", " + e.revision() + ", " + e.serialize() + ", " + e.unit() + ")");
        }
        s.indent(2);
        for(int i = 0; i < rows.size(); i++) s.line(rows.get(i) + (i < rows.size() - 1 ? "," : ""));
        s.indent(-2);
        s.line(");").blank();
        s.line("private static final EntityClass[] byId = new EntityClass[256];").blank();
        s.open("static").line("for(EntityClass c : ALL) byId[c.id()] = c;").close().blank();

        s.line("/** The class with this id, or null. */");
        s.open("public static EntityClass get(int id)").line("return id >= 0 && id < byId.length ? byId[id] : null;").close().blank();

        for(var cast : schema.casts().entrySet()){
            if(!SourceVersionCheck.identifier(cast.getKey())) throw new IllegalArgumentException("cast " + cast.getKey() + " is not a Java identifier");
            s.line("/** Whether a {@code " + cast.getKey() + "} may be of this class: packets cast entities they read to it. */");
            s.open("public static boolean is" + cast.getKey() + "(int classId)");
            if(cast.getValue().isEmpty()){
                s.line("return false;");
            }else{
                s.open("return switch(classId)").line("case " + ids(cast.getValue()) + " -> true;").line("default -> false;").close(";");
            }
            s.close().blank();
        }

        Map<String, List<Integer>> sync = new TreeMap<>(), save = new TreeMap<>();
        for(Schema.Entity e : schema.entities()){
            if(e.layout() == null) continue;
            Schema.Layout layout = schema.layouts().get(e.layout());
            if(layout == null) throw new IllegalArgumentException(e.className() + " has unknown layout " + e.layout());
            if(layout.sync() != null) sync.computeIfAbsent(e.layout(), k -> new ArrayList<>()).add(e.id());
            if(layout.save() != null) save.computeIfAbsent(e.layout(), k -> new ArrayList<>()).add(e.id());
        }

        s.line("/** A fresh holder for the class's layout. */");
        s.open("public static WireEntity newState(int classId)");
        s.open("return switch(classId)");
        Map<String, List<Integer>> any = new TreeMap<>();
        for(Schema.Entity e : schema.entities()) if(e.layout() != null) any.computeIfAbsent(e.layout(), k -> new ArrayList<>()).add(e.id());
        for(var e : any.entrySet()) s.line("case " + ids(e.getValue()) + " -> new " + e.getKey() + "Layout.State();");
        s.line("default -> throw new WireException(\"entity class \" + classId + \" has no layout\");");
        s.close(";").close().blank();

        s.line("/** {@code writeSync} of a class: {@code entity} must be its layout's {@code Source}. */");
        s.open("public static void writeSync(Writes write, int classId, WireEntity entity, WireContext context)");
        s.open("switch(classId)");
        for(var e : sync.entrySet()) s.line("case " + ids(e.getValue()) + " -> " + e.getKey() + "Layout.writeSync(write, (" + e.getKey() + "Layout.Source)entity, context);");
        s.line("default -> throw new WireException(\"entity class \" + classId + \" is never synced\");");
        s.close().close().blank();

        s.line("/** {@code readSync} of a class into a fresh holder. */");
        s.open("public static WireEntity readSync(Reads read, int classId, boolean local, WireContext context)");
        s.open("switch(classId)");
        for(var e : sync.entrySet()){
            s.open("case " + ids(e.getValue()) + " ->");
            s.line("var state = new " + e.getKey() + "Layout.State();");
            s.line(e.getKey() + "Layout.readSync(read, state, local, context);");
            s.line("return state;");
            s.close();
        }
        s.line("default -> throw new WireException(\"entity class \" + classId + \" is never synced\");");
        s.close().close().blank();

        s.line("/** {@code write} of a class: its revision, then its save body. */");
        s.open("public static void writeSave(Writes write, int classId, WireEntity entity, WireContext context)");
        s.line("EntityClass type = get(classId);");
        s.open("switch(classId)");
        for(var e : save.entrySet()){
            s.open("case " + ids(e.getValue()) + " ->");
            s.line("write.s(type.revision());");
            s.line(e.getKey() + "Layout.writeSave(write, (" + e.getKey() + "Layout.Source)entity, context);");
            s.close();
        }
        s.line("default -> throw new WireException(\"entity class \" + classId + \" is never saved\");");
        s.close().close().blank();

        s.line("/** {@code read} of a class: its revision, then the save body of that revision, the current one or an old save's. */");
        s.open("public static WireEntity readSave(Reads read, int classId, WireContext context)");
        s.line("EntityClass type = get(classId);");
        s.line("if(type == null || type.revision() < 0) throw new WireException(\"entity class \" + classId + \" is never saved\");");
        s.line("int revision = read.s();");
        s.open("switch(classId)");
        for(var e : save.entrySet()){
            s.open("case " + ids(e.getValue()) + " ->");
            s.line("var state = new " + e.getKey() + "Layout.State();");
            s.line(e.getKey() + "Layout.readSave(read, state, classId, revision, context);");
            s.line("return state;");
            s.close();
        }
        s.line("default -> throw new WireException(\"entity class \" + classId + \" is never saved\");");
        s.close().close().blank();

        s.line("/** {@code Payload.write}: false for none, else true and the payload (Payload.java:87). */");
        s.open("public static void writePayload(Writes write, Codecs.Payload payload, WireContext context)");
        s.open("switch(payload)");
        s.line("case null -> write.bool(false);");
        s.open("case Codecs.UnitPayload unit ->");
        s.line("write.bool(true);").line("write.b(0);").line("write.b(unit.classId());").line("writeSave(write, unit.classId(), unit.unit(), context);");
        s.close();
        s.open("case Codecs.BuildPayload build ->").line("write.bool(true);").line("Codecs.writeBuildPayload(write, build);").close();
        s.close().close().blank();

        s.line("/** {@code Payload.read}: a unit's class id must name a unit class (Payload.java:98). */");
        s.open("public static Codecs.Payload readPayload(Reads read, WireContext context)");
        s.line("if(!read.bool()) return null;");
        s.line("int type = read.b();");
        s.line("if(type == 1) return Codecs.readBuildPayload(read, context);");
        s.line("if(type != 0) throw new WireException(\"unknown payload type \" + type);");
        s.line("int classId = read.b();");
        s.line("EntityClass c = get(classId);");
        s.line("if(c == null || !c.unit()) throw new WireException(\"unit payload of class \" + classId + \", which is not a unit\");");
        s.line("return new Codecs.UnitPayload(classId, readSave(read, classId, context));");
        s.close().blank();

        s.line("/** {@code writeUnitContainer}: the unit's id, class id and {@code writeSync}. */");
        s.open("public static void writeSyncedUnit(Writes write, Codecs.SyncedUnit unit, WireContext context)");
        s.line("write.i(unit.id());").line("write.b(unit.classId());").line("writeSync(write, unit.classId(), unit.unit(), context);");
        s.close().blank();

        s.line("/**");
        s.line(" * {@code readUnitContainer}, as a client that doesn't control the unit. A unit the reader already has with this id is");
        s.line(" * read with its own class's layout, whatever class the body names (TypeIO.java:471); otherwise the named class must");
        s.line(" * be a unit, which vanilla casts it to, and not a block's unit, whose {@code add()} throws (BlockUnitComp.java:30).");
        s.line(" */");
        s.open("public static Codecs.SyncedUnit readSyncedUnit(Reads read, WireContext context)");
        s.line("int id = read.i(), classId = read.ub();");
        s.line("EntityClass existing = get(context.syncedClass(id)), named = get(classId);");
        s.open("if(existing != null && existing.unit())").line("classId = existing.id();");
        s.close("else if(named == null || !named.unit()){");
        s.line("    throw new WireException(\"unit container of class \" + classId + \", which is not a unit\");");
        s.line("}else if(classId == " + blockUnit + "){");
        s.line("    //quirk: vanilla adds a unit it creates here, and a block's unit refuses to be added");
        s.line("    throw new WireException(\"unit container of a new block unit\");");
        s.line("}");
        s.line("return new Codecs.SyncedUnit(id, classId, readSync(read, classId, false, context));");
        s.close();
        s.close();
        return s.toString();
    }

    static String ids(List<Integer> ids){
        return ids.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    //endregion
    //region packets

    /** A packet field: {@code server} when only the server's copy carries it. */
    record PacketField(String name, Codec codec, boolean server){}

    List<PacketField> packetFields(Schema.Packet p){
        List<PacketField> out = new ArrayList<>();
        if(p.layout().equals("(empty)")) return out;
        String[] parts = p.layout().split(", ");
        for(int i = 0; i < parts.length; i++){
            String part = parts[i];
            boolean guarded = false;
            if(part.equals(GUARD)){
                //the sender of a both-way packet: written only by the server (if(net.server()) writeEntity(player))
                if(!p.direction().equals("both") || p.framework() || i + 1 >= parts.length) throw new IllegalArgumentException(p.name() + ": a sender guard outside a both-way packet");
                part = parts[++i];
                guarded = true;
            }
            Matcher m = FIELD.matcher(part);
            if(!m.matches()) throw new IllegalArgumentException(p.name() + ": unsupported layout item '" + part + "'");
            if(guarded != m.group(1).equals("?")) throw new IllegalArgumentException(p.name() + ": '" + part + "' and its guard disagree");
            if(guarded && !(m.group(2).equals("Entity<Player>") && m.group(3).equals("player"))) throw new IllegalArgumentException(p.name() + ": guarded field " + part);
            if(PACKET_RESERVED.contains(m.group(3))) throw new IllegalArgumentException(p.name() + ": field name " + m.group(3) + " is reserved");
            out.add(new PacketField(m.group(3), codec(m.group(2), p.name()), guarded));
        }
        return out;
    }

    String packets(){
        Src s = new Src(header);
        s.line("import com.xpdustry.shard.common.io.*;").line("import com.xpdustry.shard.wire.*;")
            .line("import com.xpdustry.shard.wire.PacketType.*;").blank().line("import java.util.*;").blank();
        s.line("/**");
        s.line(" * The packet registry and one record per {@code Call} packet. Ids are positions in vanilla's registration order");
        s.line(" * ({@code Net}'s framework packets, then {@code Call.registerPackets()}), so they change with every build.");
        s.line(" * Framework packets ({@code StreamBegin}, {@code ConnectPacket}, ...) have ids here but are read by {@code :net}");
        s.line(" * and {@link Bootstrap}.");
        s.line(" */");
        s.open("public final class Packets");
        s.line("private Packets(){}").blank();
        for(Schema.Packet p : schema.packets()) s.line("public static final int " + constant(p.name()) + " = " + p.id() + ";");
        s.blank().line("/** Every packet, by id. */");
        s.line("public static final List<PacketType> TYPES = List.of(");
        s.indent(2);
        for(int i = 0; i < schema.packets().size(); i++){
            Schema.Packet p = schema.packets().get(i);
            if(p.id() != i) throw new IllegalArgumentException("packet ids must be 0..n in order: " + p.name() + " is " + p.id() + " at " + i);
            s.line("new PacketType(" + p.id() + ", \"" + p.name() + "\", Direction." + direction(p) + ", Priority." + p.priority() + ", "
                + !p.reliable().equals("udp") + ", " + p.framework() + ")" + (i < schema.packets().size() - 1 ? "," : ""));
        }
        s.indent(-2);
        s.line(");").blank();

        s.line("/** The packet with this id, or null; vanilla's {@code Net.newPacket} throws for an unknown one. */");
        s.open("public static PacketType type(int id)").line("return id >= 0 && id < TYPES.size() ? TYPES.get(id) : null;").close().blank();

        s.line("/**");
        s.line(" * Reads a {@code Call} packet body as vanilla's {@code handled()} does. Trailing bytes are left unread, as vanilla");
        s.line(" * leaves them.");
        s.line(" * @param fromServer the server sent it, so a client reads it; a packet only clients may receive is rejected");
        s.line(" *                   otherwise, as vanilla's {@code Packet.allow} does, and the other way round");
        s.line(" * @throws WireException for anything vanilla rejects: an unknown or framework id, a packet the reader may not");
        s.line(" *                       receive, a short or malformed body");
        s.line(" */");
        s.open("public static Packet read(int id, Reads read, boolean fromServer, WireContext context)");
        s.line("PacketType type = type(id);");
        s.line("if(type == null) throw new WireException(\"unknown packet id \" + id);");
        s.line("if(fromServer ? !type.direction().toClientAllowed() : !type.direction().toServerAllowed()){");
        s.line("    throw new WireException(type.name() + \" may not be sent \" + (fromServer ? \"by the server\" : \"by a client\"));");
        s.line("}");
        s.open("return WireException.decoding(type.name(), () -> switch(id)");
        for(Schema.Packet p : schema.packets()){
            if(p.framework()) continue;
            s.line("case " + p.id() + " -> " + record(p) + ".read(read, fromServer, context);");
        }
        s.line("default -> throw new WireException(type.name() + \" is a framework packet\");");
        s.close(");").close();

        for(Schema.Packet p : schema.packets()){
            if(p.framework()) continue;
            s.blank();
            packet(s, p, packetFields(p));
        }
        s.close();
        return s.toString();
    }

    void packet(Src s, Schema.Packet p, List<PacketField> fields){
        String name = record(p);
        String components = fields.stream().map(f -> f.codec.type + " " + f.name).collect(Collectors.joining(", "));
        s.line("/** {@code " + p.name() + "CallPacket}: " + describe(p) + (fields.stream().anyMatch(PacketField::server)
            ? " The server's copy starts with the sending player's id; a client's has -1." : "") + " */");
        s.open("public record " + name + "(" + components + ") implements Packet");
        s.line("public static final int ID = " + p.id() + ";").blank();
        s.line("@Override");
        s.open("public int packetId()").line("return ID;").close().blank();
        s.line("@Override");
        s.open("public void write(Writes write, boolean fromServer, WireContext context)");
        for(PacketField f : fields){
            s.line((f.server ? "if(fromServer) " : "") + statement(f.codec.write, f.name));
        }
        s.close().blank();
        s.open("public static " + name + " read(Reads read, boolean fromServer, WireContext context)");
        for(PacketField f : fields){
            if(f.server){
                s.line(f.codec.type + " " + f.name + " = fromServer ? " + f.codec.read + " : " + f.codec.init + ";");
            }else{
                s.line(f.codec.type + " " + f.name + " = " + f.codec.read + ";");
            }
        }
        s.line("return new " + name + "(" + fields.stream().map(PacketField::name).collect(Collectors.joining(", ")) + ");");
        s.close();
        s.close();
    }

    static String record(Schema.Packet p){
        return p.name();
    }

    static String direction(Schema.Packet p){
        return switch(p.direction()){
            case "C>S" -> "toServer";
            case "S>C" -> "toClient";
            case "both" -> "both";
            default -> throw new IllegalArgumentException(p.name() + ": unknown direction " + p.direction());
        };
    }

    static String describe(Schema.Packet p){
        String dir = switch(p.direction()){
            case "C>S" -> "client to server";
            case "S>C" -> "server to client";
            default -> "both ways";
        };
        return "id " + p.id() + ", " + dir + ", " + p.priority() + " priority, " + (p.reliable().equals("udp") ? "UDP" : "TCP") + ".";
    }

    //endregion

    static String constant(String name){
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replace('$', '_').toUpperCase(Locale.ROOT);
    }

    /** Java identifier rules, for enum constants. */
    static final class SourceVersionCheck{
        static boolean identifier(String name){
            return javax.lang.model.SourceVersion.isIdentifier(name) && !javax.lang.model.SourceVersion.isKeyword(name);
        }
    }

    /** Java source with Arc-style blocks: {@code open} ends a line with {@code {}, {@code close} writes the {@code }}. */
    static final class Src{
        final StringBuilder out = new StringBuilder();
        int depth;

        Src(String header){
            out.append(header);
        }

        Src line(String text){
            out.append("    ".repeat(depth)).append(text).append('\n');
            return this;
        }

        Src blank(){
            out.append('\n');
            return this;
        }

        Src open(String text){
            out.append("    ".repeat(depth)).append(text).append(text.endsWith("->") ? " {" : "{").append('\n');
            depth++;
            return this;
        }

        Src close(){
            return close("");
        }

        Src close(String suffix){
            depth--;
            return line("}" + suffix);
        }

        Src indent(int levels){
            depth += levels;
            return this;
        }

        /** Comma-separated words wrapped at 120 columns, ending with {@code end}. */
        Src wrapped(List<String> words, String end){
            StringBuilder row = new StringBuilder();
            String pad = "    ".repeat(depth);
            for(int i = 0; i < words.size(); i++){
                String word = words.get(i) + (i < words.size() - 1 ? "," : end);
                if(!row.isEmpty() && pad.length() + row.length() + 1 + word.length() > 120){
                    out.append(pad).append(row).append('\n');
                    row.setLength(0);
                }
                if(!row.isEmpty()) row.append(' ');
                row.append(word);
            }
            if(!row.isEmpty()) out.append(pad).append(row).append('\n');
            return this;
        }

        @Override
        public String toString(){
            return out.toString();
        }
    }
}
